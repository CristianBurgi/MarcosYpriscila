package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.organizer.Organizer;

/**
 * Único camino para crear un evento -- lo usa la creación sin costo del superadmin
 * (fase 9.1 Bloque 1) y lo va a usar la confirmación de pago (fase 9.4), con el
 * origen como parámetro. No puede haber un segundo camino que inserte en 'events'.
 */
public interface EventCreationService {

    /**
     * @param originReason obligatorio (no vacío) cuando origin=COURTESY; ignorado si origin=PAID.
     */
    Event createEvent(Organizer organizer, String name, EventOrigin origin, String originReason);
}
