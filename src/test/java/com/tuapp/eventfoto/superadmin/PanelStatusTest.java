package com.tuapp.eventfoto.superadmin;

import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.UploadWindow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Estado del listado del superadmin, uno por valor. Reloj fijo: viernes 6/11/2026 a las 10:00 de Argentina
 * (13:00 UTC), con la JVM en UTC (-Duser.timezone=UTC en el pom) como en Railway.
 */
class PanelStatusTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);
    private static final UploadWindow WINDOW = new UploadWindow(
            Clock.fixed(LocalDateTime.of(2026, 11, 6, 10, 0).atZone(UploadWindow.ZONE).toInstant(), UploadWindow.ZONE));

    @BeforeAll
    static void jvmInUtc() {
        assertThat(TimeZone.getDefault().getRawOffset()).as("la JVM de los tests corre en UTC").isZero();
    }

    private static Event.EventBuilder configured(LocalDate eventDate) {
        return Event.builder().eventDate(eventDate).isActive(true).wizardCompletedAt(Instant.parse("2026-10-01T12:00:00Z"));
    }

    private static PanelStatus status(Event event) {
        return PanelStatus.of(event, WINDOW);
    }

    @Test
    @DisplayName("Wizard sin completar (con o sin fecha) -> sin configurar")
    void notConfigured() {
        assertThat(status(Event.builder().isActive(true).build())).isEqualTo(PanelStatus.NOT_CONFIGURED);
        assertThat(status(configured(TODAY).wizardCompletedAt(null).build())).isEqualTo(PanelStatus.NOT_CONFIGURED);
        assertThat(PanelStatus.NOT_CONFIGURED.label).isEqualTo("sin configurar");
    }

    @Test
    @DisplayName("Evento dentro de dos días (la subida abre mañana a las 00:00) -> esperando la fecha")
    void waiting() {
        assertThat(status(configured(TODAY.plusDays(2)).build())).isEqualTo(PanelStatus.WAITING);
    }

    @Test
    @DisplayName("Evento hoy -> subida abierta")
    void open() {
        assertThat(status(configured(TODAY).build())).isEqualTo(PanelStatus.OPEN);
    }

    @Test
    @DisplayName("Evento hoy pero desactivado por el organizador -> cerrado por el organizador")
    void closedByOrganizer() {
        assertThat(status(configured(TODAY).isActive(false).build())).isEqualTo(PanelStatus.CLOSED_BY_ORGANIZER);
    }

    @Test
    @DisplayName("Evento hace 2 días (cerró anoche a las 00:00), sin vencer -> subida cerrada")
    void closed() {
        assertThat(status(configured(TODAY.minusDays(2)).build())).isEqualTo(PanelStatus.CLOSED);
    }

    @Test
    @DisplayName("Fecha de borrado alcanzada (evento + 30 días = hoy) sin que el job haya corrido -> vencido")
    void expired() {
        assertThat(status(configured(TODAY.minusDays(30)).build())).isEqualTo(PanelStatus.EXPIRED);
        assertThat(status(configured(TODAY.minusDays(29)).build())).as("un día antes").isEqualTo(PanelStatus.CLOSED);
        // Vencido gana sobre "cerrado por el organizador".
        assertThat(status(configured(TODAY.minusDays(40)).isActive(false).build())).isEqualTo(PanelStatus.EXPIRED);
    }

    @Test
    @DisplayName("Con extensión: el mismo evento vencido vuelve a subida cerrada")
    void extensionUnexpires() {
        assertThat(status(configured(TODAY.minusDays(40)).retentionOverrideUntil(TODAY.plusDays(10)).build()))
                .isEqualTo(PanelStatus.CLOSED);
    }

    @Test
    @DisplayName("purged_at -> borrado, aunque tenga extensión o el wizard sin completar")
    void purged() {
        assertThat(status(configured(TODAY.minusDays(31)).purgedAt(Instant.parse("2026-11-05T06:00:00Z")).build()))
                .isEqualTo(PanelStatus.PURGED);
        assertThat(status(Event.builder().isActive(true).purgedAt(Instant.parse("2026-11-05T06:00:00Z"))
                .retentionOverrideUntil(TODAY.plusDays(5)).build())).isEqualTo(PanelStatus.PURGED);
    }
}
