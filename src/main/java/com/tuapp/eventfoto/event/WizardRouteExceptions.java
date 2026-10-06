package com.tuapp.eventfoto.event;

import java.util.List;

/**
 * Rutas del panel de UN evento que siguen disponibles mientras el wizard no está completo
 * ({@code wizard_completed_at} NULL). Todas las demás rutas con {slug} redirigen al wizard (vistas)
 * o responden 409 WIZARD_REQUIRED (API): ver EventAccessInterceptor.
 *
 * Patrones EXACTOS, todos con {slug} (el acceso al evento se sigue verificando igual). Los endpoints de
 * fecha, color e imagen son los mismos que usa la tarjeta "Personalización" del panel.
 */
public final class WizardRouteExceptions {

    public record Entry(String pattern, String reason) {
    }

    public static final List<Entry> ENTRIES = List.of(
            new Entry("/admin/eventos/{slug}/wizard",
                    "La vista del wizard: es a donde redirige el bloqueo."),
            new Entry("/api/v1/admin/events/{slug}/date",
                    "Paso 1 del wizard: la fecha del evento."),
            new Entry("/api/v1/admin/events/{slug}/branding/color",
                    "Paso 2 del wizard: el color del evento."),
            new Entry("/api/v1/admin/events/{slug}/branding/image",
                    "Paso 2 del wizard: subir o quitar la imagen de fondo."),
            new Entry("/api/v1/admin/events/{slug}/wizard/complete",
                    "Paso 3 del wizard: \"Ir a mi panel\" marca el wizard como completo."));

    private WizardRouteExceptions() {
    }

    public static boolean isException(String pattern) {
        return pattern != null && ENTRIES.stream().anyMatch(entry -> entry.pattern().equals(pattern));
    }
}
