package com.tuapp.eventfoto.event;

import java.util.List;

/**
 * Rutas de /admin/** y /api/v1/admin/** que NO pasan por la resolución de evento de
 * EventAccessInterceptor. Cualquier otra ruta de esos prefijos DEBE llevar {slug}; si no,
 * el interceptor la cierra con 404 y el test AdminRouteEnumerationTest rompe el build.
 *
 * Son patrones EXACTOS (se comparan con el patrón de la ruta mapeada, nunca por prefijo ni
 * con comodines): una excepción no puede cubrir por accidente una ruta nueva.
 * Ninguna puede contener {slug}.
 */
public final class AdminRouteExceptions {

    public record Entry(String pattern, String reason) {
    }

    public static final List<Entry> ENTRIES = List.of(
            new Entry("/admin/login",
                    "Página de login: todavía no hay sesión ni evento."),
            new Entry("/api/v1/admin/auth/login",
                    "Login del organizador: no hay evento ni sesión todavía."),
            new Entry("/api/v1/admin/auth/logout",
                    "Logout: borra la cookie de sesión, no opera sobre ningún evento."),
            new Entry("/admin/eventos",
                    "\"Mis eventos\": no es un evento puntual; la consulta ya viene filtrada por el organizador logueado (findSummariesByOrganizerId)."),
            new Entry("/admin",
                    "Solo redirige a /admin/eventos."),
            new Entry("/admin/",
                    "Solo redirige a /admin/eventos."));

    private AdminRouteExceptions() {
    }

    public static boolean isException(String pattern) {
        return pattern != null && ENTRIES.stream().anyMatch(entry -> entry.pattern().equals(pattern));
    }
}
