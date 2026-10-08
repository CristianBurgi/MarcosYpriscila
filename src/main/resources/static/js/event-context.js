// Toma el evento de la URL (/e/{slug}/...). Es la única fuente del slug en las páginas
// de invitado y en la pantalla del salón: nada de slugs escritos a mano.
//
// Fase 9.7-B: las páginas arman sus requests con EVENT_API y sus links con EVENT_BASE. En un evento real salen
// del slug, con los mismos valores de siempre. La demo (/demo/{sid}) los define en un <script> inyectado ANTES de
// este archivo, por eso acá solo se completan si no vienen definidos. EVENT_STORAGE_PREFIX separa las claves de
// localStorage de cada demo de las de los eventos reales (en un evento real es "": las claves no cambian).
(function () {
    var match = window.location.pathname.match(/^\/e\/([a-z0-9-]+)/);
    window.EVENT_SLUG = match ? match[1] : '';
    if (window.EVENT_API === undefined) window.EVENT_API = '/api/v1/events/' + window.EVENT_SLUG;
    if (window.EVENT_BASE === undefined) window.EVENT_BASE = '/e/' + window.EVENT_SLUG;
    if (window.EVENT_STORAGE_PREFIX === undefined) window.EVENT_STORAGE_PREFIX = '';

    // Los links del menú se arman con rutas absolutas: un href relativo bajo /e/{slug}
    // resolvería contra /e/ y se rompería.
    document.addEventListener('DOMContentLoaded', function () {
        document.querySelectorAll('[data-event-path]').forEach(function (a) {
            a.setAttribute('href', window.EVENT_BASE + a.getAttribute('data-event-path'));
        });
    });
})();
