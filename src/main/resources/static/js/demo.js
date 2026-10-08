// Fase 9.7-B: lo que solo existe en las páginas de la demo. Lo carga DemoPageController (nunca una página de un
// evento real): el aviso "Esto es una demo…" con los minutos que quedan, el fin de la demo y los agregados del menú
// y de la pantalla. Las páginas de invitados no saben nada de esto.
(function () {
    var page = window.DEMO_PAGE;
    var ENDED = '/demo?fin=1';
    var clockOffset = 0; // hora del servidor - hora de este equipo, para que los minutos sean exactos

    function el(tag, attrs, text) {
        var node = document.createElement(tag);
        Object.keys(attrs || {}).forEach(function (k) { node.setAttribute(k, attrs[k]); });
        if (text) node.textContent = text;
        return node;
    }

    var note = el('div', { 'class': 'demo-note', role: 'status' }, 'Esto es una demo. Nadie más la ve.');
    var expiresAt = null;

    function renderNote() {
        if (expiresAt === null) return;
        var left = expiresAt - (Date.now() + clockOffset);
        if (left <= 0) { location.href = ENDED; return; }
        var minutes = Math.ceil(left / 60000);
        note.textContent = 'Esto es una demo. Se borra sola en ' + minutes + (minutes === 1 ? ' minuto' : ' minutos') + ' y nadie más la ve.';
    }

    // Cada 30 s: minutos que quedan y, si la demo ya no existe (venció o la vaciaron), a "Tu demo terminó".
    function refresh() {
        fetch(window.EVENT_API, { cache: 'no-store' }).then(function (res) {
            if (res.status === 404) { location.href = ENDED; return null; }
            var serverDate = Date.parse(res.headers.get('Date'));
            if (!isNaN(serverDate)) clockOffset = serverDate - Date.now();
            return res.ok ? res.json() : null;
        }).then(function (demo) {
            if (demo && demo.expiresAt) { expiresAt = Date.parse(demo.expiresAt); renderNote(); }
        }).catch(function () { /* sin red: se reintenta en la próxima vuelta */ });
    }

    function addMenuExtras() {
        var menu = document.querySelector('.menu-container');
        if (!menu) return;
        var screen = el('a', { href: window.EVENT_BASE + '/pantalla', 'class': 'btn-elegant' }, 'Ver la pantalla');
        menu.appendChild(screen);
        menu.appendChild(el('div', { 'class': 'divider' }, 'En tu evento, además'));
        [['Descargar todas las fotos (ZIP)'], ['Libro de visitas en PDF']].forEach(function (item) {
            var disabled = el('div', { 'class': 'btn-elegant demo-disabled', 'aria-disabled': 'true' }, item[0]);
            disabled.appendChild(el('small', {}, 'Disponible en tu evento'));
            menu.appendChild(disabled);
        });
    }

    function addScreenExtras() {
        document.body.appendChild(el('a', { href: window.EVENT_BASE + '/subir', 'class': 'demo-mobile-upload' }, 'Subir una foto desde este celular'));
        document.body.appendChild(el('a', { href: window.EVENT_BASE, 'class': 'demo-back' }, '← Menú'));
    }

    document.addEventListener('DOMContentLoaded', function () {
        document.body.classList.add('demo-page', 'demo-' + page);
        document.body.appendChild(note);
        if (page === 'menu') addMenuExtras();
        if (page === 'screen') addScreenExtras();
        refresh();
        setInterval(refresh, 30000);
        setInterval(renderNote, 5000);
    });
})();
