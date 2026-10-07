package com.tuapp.eventfoto.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static com.tuapp.eventfoto.event.UploadWindow.ZONE;

/**
 * Reloj fijo que el test mueve a mano, en UTC (como Railway). Para usarlo en un @SpringBootTest:
 * {@code @Import(MutableClock.Config.class)} y {@code @Autowired MutableClock clock}.
 */
public class MutableClock extends Clock {

    private volatile Instant now = Instant.now();

    /** Fija "ahora" a esa hora de Argentina. */
    public void setArgentina(LocalDateTime argentina) {
        now = argentina.atZone(ZONE).toInstant();
    }

    public void set(Instant instant) {
        now = instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        public MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}
