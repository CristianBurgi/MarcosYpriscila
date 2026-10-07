package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.common.exception.EventClosedException;
import com.tuapp.eventfoto.testsupport.MutableClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.TimeZone;

import static com.tuapp.eventfoto.event.UploadWindow.GuestWrite.COMMENT;
import static com.tuapp.eventfoto.event.UploadWindow.GuestWrite.MESSAGE;
import static com.tuapp.eventfoto.event.UploadWindow.GuestWrite.PHOTO_FINISH;
import static com.tuapp.eventfoto.event.UploadWindow.GuestWrite.PHOTO_START;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fase 9.6: límites de la ventana de escritura y del vencimiento, con la JVM en UTC (como Railway) y reloj fijo. */
class UploadWindowTest {

    private static TimeZone original;

    private final MutableClock clock = new MutableClock();
    private final UploadWindow window = new UploadWindow(clock);

    @BeforeAll
    static void jvmInUtc() {
        original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @AfterAll
    static void restore() {
        TimeZone.setDefault(original);
    }

    private static Event event(LocalDate date) {
        return Event.builder().name("Evento").eventDate(date).isActive(true).build();
    }

    private UploadWindow.Status at(Event event, String argentina) {
        clock.setArgentina(LocalDateTime.parse(argentina));
        return window.status(event);
    }

    @Test
    @DisplayName("Evento el 15/11: cerrada el 13/11 23:59, abierta del 14/11 00:00 al 16/11 23:59, cerrada el 17/11 00:00 (hora de Argentina)")
    void windowBoundaries() {
        Event event = event(LocalDate.of(2026, 11, 15));
        assertThat(at(event, "2026-11-13T23:59:59")).isEqualTo(UploadWindow.Status.NOT_OPEN);
        assertThat(at(event, "2026-11-14T00:00:00")).isEqualTo(UploadWindow.Status.OPEN);
        assertThat(at(event, "2026-11-16T23:59:59")).isEqualTo(UploadWindow.Status.OPEN);
        assertThat(at(event, "2026-11-17T00:00:00")).isEqualTo(UploadWindow.Status.CLOSED);
        // En UTC el cierre es 17/11 03:00: a las 02:59 UTC (23:59 en Argentina) sigue abierta.
        clock.set(Instant.parse("2026-11-17T02:59:59Z"));
        assertThat(window.status(event)).isEqualTo(UploadWindow.Status.OPEN);
    }

    @Test
    @DisplayName("Sin fecha: cerrada (EVENT_NOT_READY). isActive=false: cerrada (EVENT_CLOSED) aunque esté en fecha")
    void noDateAndManualClose() {
        clock.setArgentina(LocalDateTime.parse("2026-11-15T12:00:00"));
        assertThat(window.status(event(null))).isEqualTo(UploadWindow.Status.NOT_READY);
        Event closed = event(LocalDate.of(2026, 11, 15));
        closed.setActive(false);
        assertThat(window.status(closed)).isEqualTo(UploadWindow.Status.CLOSED_BY_ORGANIZER);
        assertThatThrownBy(() -> window.assertGuestCanWrite(closed, MESSAGE))
                .isInstanceOf(EventClosedException.class).extracting("code").isEqualTo("EVENT_CLOSED");
    }

    @Test
    @DisplayName("Gracia de /confirm y upload-direct: 15 minutos después del cierre se acepta, 16 se rechaza; upload-url no tiene gracia")
    void closeGrace() {
        Event event = event(LocalDate.of(2026, 11, 15));
        assertThat(UploadWindow.CLOSE_GRACE).hasMinutes(15);

        clock.setArgentina(LocalDateTime.parse("2026-11-17T00:15:00"));
        assertThatCode(() -> window.assertGuestCanWrite(event, PHOTO_FINISH)).doesNotThrowAnyException();
        assertThatThrownBy(() -> window.assertGuestCanWrite(event, PHOTO_START)).extracting("code").isEqualTo("UPLOAD_CLOSED");
        assertThatThrownBy(() -> window.assertGuestCanWrite(event, MESSAGE)).extracting("code").isEqualTo("UPLOAD_CLOSED");

        clock.setArgentina(LocalDateTime.parse("2026-11-17T00:16:00"));
        assertThatThrownBy(() -> window.assertGuestCanWrite(event, PHOTO_FINISH)).extracting("code").isEqualTo("UPLOAD_CLOSED");
    }

    @Test
    @DisplayName("Textos para el invitado, en castellano y con la fecha de apertura en hora de Argentina")
    void messages() {
        Event event = event(LocalDate.of(2026, 11, 15));
        clock.setArgentina(LocalDateTime.parse("2026-11-10T10:00:00"));
        assertThatThrownBy(() -> window.assertGuestCanWrite(event, PHOTO_START)).hasMessage(
                "La subida de fotos todavía no está habilitada. Se abre el sábado 14 de noviembre a las 00:00.");
        assertThatThrownBy(() -> window.assertGuestCanWrite(event, MESSAGE)).hasMessage(
                "El libro de visitas todavía no está habilitado. Se abre el sábado 14 de noviembre a las 00:00.");
        assertThatThrownBy(() -> window.assertGuestCanWrite(event, COMMENT)).hasMessage(
                "Los comentarios todavía no están habilitados. Se abren el sábado 14 de noviembre a las 00:00.");

        clock.setArgentina(LocalDateTime.parse("2026-11-20T10:00:00"));
        assertThat(window.closedMessage(event, PHOTO_START)).isEqualTo("La subida de fotos ya cerró.");
        assertThat(window.closedMessage(event, MESSAGE)).isEqualTo("El libro de visitas ya cerró.");
        assertThat(window.closedMessage(event, COMMENT)).isEqualTo("Los comentarios ya cerraron.");
        assertThat(window.closedMessage(event(null), PHOTO_START)).isEqualTo("Este evento todavía no está listo para recibir fotos.");
    }

    @Test
    @DisplayName("Vencimiento: eventDate + 30 días (o retention_override_until), desde las 00:00 de ese día en Argentina; sin fecha no vence")
    void expiry() {
        Event event = event(LocalDate.of(2026, 11, 15));
        assertThat(window.deletionDate(event)).isEqualTo(LocalDate.of(2026, 12, 15));

        clock.setArgentina(LocalDateTime.parse("2026-12-14T23:59:59"));
        assertThat(window.isExpired(event)).isFalse();
        clock.setArgentina(LocalDateTime.parse("2026-12-15T00:00:00"));
        assertThat(window.isExpired(event)).isTrue();

        event.setRetentionOverrideUntil(LocalDate.of(2027, 1, 31));
        assertThat(window.isExpired(event)).isFalse();
        assertThat(window.deletionDate(event)).isEqualTo(LocalDate.of(2027, 1, 31));

        assertThat(window.isExpired(event(null))).isFalse();
        Event purged = event(LocalDate.of(2026, 12, 1));
        purged.setPurgedAt(Instant.now());
        assertThat(window.isExpired(purged)).isTrue();
    }
}
