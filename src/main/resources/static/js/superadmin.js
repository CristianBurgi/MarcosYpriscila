// Acciones del panel del superadmin: cada botón con data-action abre el diálogo, confirma y hace el POST.
(() => {
    const dlg = document.getElementById('dlg');
    if (!dlg) return;
    const $ = id => document.getElementById(id);
    const TODAY = document.body.dataset.today || '';
    const day = iso => iso.split('-').reverse().join('/');
    let current = null;
    let changed = false;

    // Cada acción: textos de la confirmación, si pide un dato, a qué endpoint va y cómo mostrar el resultado.
    const ACTIONS = {
        'retention': d => ({
            title: 'Extender vigencia',
            text: d.purged === 'true'
                ? `"${d.name}" ya se borró el ${day(d.current)}.`
                : `Nueva fecha de borrado de "${d.name}". Hoy se borra el ${day(d.current)}. Si el recordatorio ya salió, sale otro 5 días antes de la fecha nueva.`,
            input: {type: 'date', value: d.current, min: TODAY, max: d.max},
            url: `/api/v1/superadmin/events/${d.id}/retention`,
            body: v => ({until: v}),
            ok: r => `Listo: se borra el ${day(r.deletionDate)}.` + (r.reminderReset ? ' El recordatorio se reinició.' : '')
        }),
        'activation': d => ({
            title: 'Generar link de activación',
            text: `Se genera un link nuevo para ${d.email}. El anterior deja de funcionar. El link se muestra una sola vez.`,
            url: `/api/v1/superadmin/organizers/${d.id}/resend-activation`,
            okHtml: r => linkBox('Link nuevo (copialo ahora, no se vuelve a mostrar):', r.activationLink)
        }),
        'resend-mail': d => ({
            title: 'Reenviar mail de confirmación',
            text: `Se reenvía "Tu evento está listo" de "${d.name}" a ${d.email}.`,
            url: `/api/v1/superadmin/events/${d.id}/resend-confirmation`,
            ok: () => 'Mail reenviado.'
        }),
        'confirm-payment': d => ({
            title: 'Marcar pago aprobado',
            text: 'Consulta el pago real en Mercado Pago y, si está aprobado y todo cierra, crea la cuenta y el evento (la misma validación que el webhook).',
            input: {type: 'text', value: d.payment || '', placeholder: 'payment_id', inputmode: 'numeric'},
            url: '/api/v1/superadmin/payments/confirm',
            body: v => ({paymentId: v}),
            ok: r => OUTCOMES[r.outcome] || r.outcome
        }),
        'resolve': d => ({
            title: 'Marcar incidente resuelto',
            text: `El incidente del pago ${d.payment} sale de la lista. No hace nada más: la revisión y un reembolso, si corresponde, son a mano.`,
            url: `/api/v1/superadmin/incidents/${d.id}/resolve`,
            ok: r => r.resolvedNow ? 'Incidente resuelto.' : 'Ya estaba resuelto.'
        }),
        'demo-clear': () => ({
            title: 'Vaciar demo',
            text: 'Borra todas las demos ahora: sus fotos y fondos en R2, sus mensajes y sus sesiones. Las pantallas abiertas pasan a "Tu demo terminó". No se puede deshacer.',
            url: '/api/v1/superadmin/demo/clear',
            ok: r => `Demos borradas: ${r.sessions}. Objetos en storage: ${r.objects}. Con error: ${r.failed}.`
        }),
        'lifecycle': () => ({
            title: 'Correr el ciclo de vida ahora',
            text: 'Manda los recordatorios pendientes y borra los álbumes vencidos (fotos, mensajes y comentarios). No se puede deshacer.',
            url: '/api/v1/superadmin/lifecycle/run',
            ok: r => `Recordatorios: ${r.reminders}. Borrados: ${r.purged}. Con error: ${r.failed}.`
        })
    };

    const OUTCOMES = {
        CREATED: 'Creado: la cuenta y el evento ya existen y salió el mail.',
        ALREADY_PROCESSED: 'Ya procesado: esa compra ya estaba confirmada con este pago.',
        NOT_APPROVED: 'No aprobado: Mercado Pago no da ese pago como aprobado. No se creó nada.',
        INCIDENT: 'Incidente: el pago necesita revisión manual. No se creó nada.',
        UNKNOWN_PAYMENT: 'Pago desconocido: Mercado Pago no conoce ese payment_id.'
    };

    function open(action, data) {
        current = ACTIONS[action](data);
        $('dlgTitle').textContent = current.title;
        $('dlgText').textContent = current.text;
        const input = $('dlgInput');
        input.style.display = current.input ? '' : 'none';
        if (current.input) {
            ['min', 'max', 'placeholder', 'inputmode'].forEach(a => input.removeAttribute(a));
            Object.entries(current.input).forEach(([k, v]) => v != null && input.setAttribute(k, v));
            input.value = current.input.value || '';
        }
        $('dlgResult').className = 'notice';
        $('dlgResult').replaceChildren();
        $('dlgOk').style.display = '';
        $('dlgOk').disabled = false;
        $('dlgCancel').textContent = 'Cancelar';
        dlg.showModal();
    }

    function show(kind, content) {
        $('dlgResult').className = 'notice ' + kind;
        $('dlgResult').replaceChildren(typeof content === 'string' ? document.createTextNode(content) : content);
    }

    $('dlgOk').onclick = async () => {
        const value = $('dlgInput').value.trim();
        if (current.input && !value) {
            show('err', 'Completá el dato.');
            return;
        }
        $('dlgOk').disabled = true;
        try {
            const res = await fetch(current.url, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(current.body ? current.body(value) : {})
            });
            const data = await res.json().catch(() => ({}));
            if (res.status === 401) {
                location.href = '/superadmin/login';
                return;
            }
            if (!res.ok) {
                show('err', data.message || `Error ${res.status}`);
                $('dlgOk').disabled = false;
                return;
            }
            changed = true;
            show('ok', current.okHtml ? current.okHtml(data) : current.ok(data));
            $('dlgOk').style.display = 'none';
            $('dlgCancel').textContent = 'Cerrar';
        } catch (e) {
            show('err', 'Error de conexión con el servidor.');
            $('dlgOk').disabled = false;
        }
    };

    $('dlgCancel').onclick = () => dlg.close();
    dlg.addEventListener('close', () => { if (changed) location.reload(); });

    document.addEventListener('click', e => {
        const btn = e.target.closest('button[data-action]');
        if (btn) open(btn.dataset.action, btn.dataset);
    });
})();

// Caja con un link para copiar (link de activación). También la usa "Crear evento sin costo".
function linkBox(label, link) {
    const wrap = document.createElement('div');
    wrap.append(Object.assign(document.createElement('p'), {textContent: label}));
    const box = document.createElement('div');
    box.className = 'link-box';
    const input = Object.assign(document.createElement('input'), {type: 'text', readOnly: true, value: link});
    input.setAttribute('aria-label', 'Link de activación');
    const copy = Object.assign(document.createElement('button'), {type: 'button', className: 'btn btn-sm', textContent: 'Copiar'});
    copy.onclick = () => { input.select(); navigator.clipboard.writeText(link).then(() => copy.textContent = 'Copiado'); };
    box.append(input, copy);
    wrap.append(box);
    return wrap;
}
