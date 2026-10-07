package com.tuapp.eventfoto.testsupport;

import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.event.UploadWindow;
import com.tuapp.eventfoto.organizer.Organizer;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Eventos de prueba con el límite de fotos por invitado puesto de forma EXPLÍCITA. En Event el campo no tiene
 * default (null = sin límite), así que un test que arma el evento a mano y espera un rechazo por cupo recibiría
 * una aceptación: con estos helpers el límite se declara en la propia llamada y no hay "sin límite" por omisión.
 */
public final class TestEvents {

    private TestEvents() {
    }

    /** Evento pago, abierto, sin límite decidido: solo para tests que no tocan el cupo. */
    public static Event.EventBuilder base(Organizer organizer, String slug) {
        return Event.builder()
                .organizer(organizer).name("Evento " + slug).slug(slug)
                .eventDate(LocalDate.now(UploadWindow.ZONE)) // hoy en Argentina: ventana de subida abierta a cualquier hora
                .isActive(true).origin(EventOrigin.PAID)
                .wizardCompletedAt(Instant.now()); // wizard hecho: el panel responde (ver EventAccessInterceptor)
    }

    public static Event limited(EventRepository repo, Organizer organizer, String slug, int maxPhotosPerGuest) {
        return repo.save(base(organizer, slug).maxPhotosPerGuest(maxPhotosPerGuest).build());
    }

    public static Event unlimited(EventRepository repo, Organizer organizer, String slug) {
        return repo.save(base(organizer, slug).maxPhotosPerGuest(null).build());
    }
}
