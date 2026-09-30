// Toma el evento de la URL (/e/{slug}/...). Es la única fuente del slug en las páginas
// de invitado y en la pantalla del salón: nada de slugs escritos a mano.
(function () {
    var match = window.location.pathname.match(/^\/e\/([a-z0-9-]+)/);
    window.EVENT_SLUG = match ? match[1] : '';

    // Los links del menú se arman con rutas absolutas: un href relativo bajo /e/{slug}
    // resolvería contra /e/ y se rompería.
    document.addEventListener('DOMContentLoaded', function () {
        document.querySelectorAll('[data-event-path]').forEach(function (a) {
            a.setAttribute('href', '/e/' + window.EVENT_SLUG + a.getAttribute('data-event-path'));
        });
    });
})();
