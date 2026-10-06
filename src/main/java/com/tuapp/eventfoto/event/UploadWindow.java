package com.tuapp.eventfoto.event;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Único lugar que calcula fechas del evento en hora de Argentina. La zona es explícita: Railway corre en UTC y
 * "hoy" no puede depender del servidor. El {@link Clock} es inyectable para los tests de límites.
 * La 9.6 lo extiende con el cierre de la ventana de subida real.
 */
@Component
@RequiredArgsConstructor
public class UploadWindow {

    public static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    private final Clock clock;

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZONE));
    }

    /** La subida de fotos se habilita a las 00:00 del día anterior al evento, hora de Argentina. */
    public Instant opensAt(LocalDate eventDate) {
        return eventDate.minusDays(1).atStartOfDay(ZONE).toInstant();
    }

    public boolean hasOpened(LocalDate eventDate) {
        return !clock.instant().isBefore(opensAt(eventDate));
    }
}
