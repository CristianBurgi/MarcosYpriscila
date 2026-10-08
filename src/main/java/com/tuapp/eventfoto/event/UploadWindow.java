package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.common.exception.EventClosedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Único lugar que calcula fechas del evento en hora de Argentina. La zona es explícita: Railway corre en UTC y
 * "hoy" no puede depender del servidor. El {@link Clock} es inyectable para los tests de límites.
 *
 * <ul>
 *   <li>Ventana de escritura de invitados: [00:00 del día anterior al evento, 00:00 de dos días después), con
 *       isActive=true. Se calcula en cada request ({@link #assertGuestCanWrite}).</li>
 *   <li>Fecha de borrado: retention_override_until o eventDate + 30 días. Ese día el álbum vence ({@link #isExpired})
 *       aunque el job todavía no haya corrido. Un evento sin fecha no vence.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class UploadWindow {

    public static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    /**
     * Gracia de /confirm y upload-direct después del cierre: un PUT empezado a las 23:59 con mala señal y los
     * reintentos del front (2 s, 5 s, 10 s) tiene que poder terminar. upload-url (empezar una subida) es estricto.
     */
    public static final Duration CLOSE_GRACE = Duration.ofMinutes(15);

    public static final int RETENTION_DAYS = 30;
    public static final int REMINDER_DAYS_BEFORE = 5;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale.forLanguageTag("es-AR"));

    private final Clock clock;

    public enum Status {
        OPEN(null), NOT_READY("EVENT_NOT_READY"), NOT_OPEN("UPLOAD_NOT_OPEN"), CLOSED("UPLOAD_CLOSED"),
        CLOSED_BY_ORGANIZER("EVENT_CLOSED");

        public final String code;

        Status(String code) {
            this.code = code;
        }
    }

    /** Qué escribe el invitado: define la gracia y los textos. */
    public enum GuestWrite {
        PHOTO_START(Duration.ZERO, "La subida de fotos todavía no está habilitada. Se abre el %s.", "La subida de fotos ya cerró.",
                "fotos", "La recepción de fotografías para este evento se encuentra cerrada por el organizador."),
        PHOTO_FINISH(CLOSE_GRACE, PHOTO_START),
        MESSAGE(Duration.ZERO, "El libro de visitas todavía no está habilitado. Se abre el %s.", "El libro de visitas ya cerró.",
                "mensajes", "El libro de visitas está cerrado por el organizador."),
        COMMENT(Duration.ZERO, "Los comentarios todavía no están habilitados. Se abren el %s.", "Los comentarios ya cerraron.",
                "comentarios", "Los comentarios están cerrados por el organizador.");

        private final Duration grace;
        private final String notOpen;
        private final String closed;
        private final String what;
        private final String closedByOrganizer;

        GuestWrite(Duration grace, String notOpen, String closed, String what, String closedByOrganizer) {
            this.grace = grace;
            this.notOpen = notOpen;
            this.closed = closed;
            this.what = what;
            this.closedByOrganizer = closedByOrganizer;
        }

        GuestWrite(Duration grace, GuestWrite texts) {
            this(grace, texts.notOpen, texts.closed, texts.what, texts.closedByOrganizer);
        }

        String message(Status status, LocalDate eventDate) {
            return switch (status) {
                case OPEN -> null;
                case NOT_READY -> "Este evento todavía no está listo para recibir " + what + ".";
                case NOT_OPEN -> notOpen.formatted(day(eventDate.minusDays(1)) + " a las 00:00");
                case CLOSED -> closed;
                case CLOSED_BY_ORGANIZER -> closedByOrganizer;
            };
        }
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZONE));
    }

    /** La subida de fotos se habilita a las 00:00 del día anterior al evento, hora de Argentina. */
    public Instant opensAt(LocalDate eventDate) {
        return eventDate.minusDays(1).atStartOfDay(ZONE).toInstant();
    }

    /** Y cierra a las 00:00 de dos días después del evento: el día siguiente entero (hasta las 23:59:59) sigue abierto. */
    public Instant closesAt(LocalDate eventDate) {
        return eventDate.plusDays(2).atStartOfDay(ZONE).toInstant();
    }

    /** La subida todavía no cerró (sin gracia). Hasta acá se muestra la checklist previa del panel (9.8). */
    public boolean hasNotClosed(LocalDate eventDate) {
        return clock.instant().isBefore(closesAt(eventDate));
    }

    public boolean hasOpened(LocalDate eventDate) {
        return !clock.instant().isBefore(opensAt(eventDate));
    }

    public Status status(Event event) {
        return status(event, Duration.ZERO);
    }

    private Status status(Event event, Duration grace) {
        if (!event.isActive()) {
            return Status.CLOSED_BY_ORGANIZER;
        }
        if (event.getEventDate() == null) {
            return Status.NOT_READY;
        }
        Instant now = clock.instant();
        if (now.isBefore(opensAt(event.getEventDate()))) {
            return Status.NOT_OPEN;
        }
        // El cierre es exacto (00:00 ya está cerrado); la gracia, si hay, incluye su último instante (+15:00 se acepta).
        Instant closes = closesAt(event.getEventDate());
        if (!now.isBefore(closes) && (grace.isZero() || now.isAfter(closes.plus(grace)))) {
            return Status.CLOSED;
        }
        return Status.OPEN;
    }

    /** Mensaje para el invitado si hoy no puede escribir, o {@code null} si puede (sin gracia). */
    public String closedMessage(Event event, GuestWrite write) {
        return write.message(status(event), event.getEventDate());
    }

    /** El chequeo de toda escritura de invitados (fotos, mensajes, comentarios). */
    public void assertGuestCanWrite(Event event, GuestWrite write) {
        Status status = status(event, write.grace);
        if (status != Status.OPEN) {
            throw new EventClosedException(status.code, write.message(status, event.getEventDate()));
        }
    }

    /** {@code null} si el evento no vence (sin fecha y sin fecha de borrado fijada). */
    public LocalDate deletionDate(Event event) {
        if (event.getRetentionOverrideUntil() != null) {
            return event.getRetentionOverrideUntil();
        }
        return event.getEventDate() != null ? event.getEventDate().plusDays(RETENTION_DAYS) : null;
    }

    /** Borrado ya hecho, o la fecha de borrado ya llegó aunque el job todavía no haya corrido. */
    public boolean isExpired(Event event) {
        if (event.getPurgedAt() != null) {
            return true;
        }
        LocalDate deletion = deletionDate(event);
        return deletion != null && !today().isBefore(deletion);
    }

    /** "sábado 14 de noviembre". */
    public static String day(LocalDate date) {
        return DAY.format(date);
    }
}
