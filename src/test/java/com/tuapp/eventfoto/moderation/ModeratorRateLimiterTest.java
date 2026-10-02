package com.tuapp.eventfoto.moderation;

import com.github.benmanes.caffeine.cache.Ticker;
import com.tuapp.eventfoto.common.exception.RateLimitExceededException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModeratorRateLimiterTest {

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private final ModeratorRateLimiter limiter = new ModeratorRateLimiter(ticker);

    private void advanceMinutes(long minutes) {
        nanos.addAndGet(minutes * 60_000_000_000L);
    }

    @Test
    @DisplayName("20 intentos inválidos de una IP la bloquean (429); otra IP no se ve afectada; a los 10 minutos se libera sola")
    void invalidAttemptsBlockOnlyThatIpAndExpire() {
        for (int i = 0; i < 19; i++) {
            limiter.recordInvalidAttempt("1.1.1.1");
        }
        assertThatCode(() -> limiter.checkNotBlocked("1.1.1.1")).doesNotThrowAnyException();
        limiter.recordInvalidAttempt("1.1.1.1");
        assertThatThrownBy(() -> limiter.checkNotBlocked("1.1.1.1")).isInstanceOf(RateLimitExceededException.class);
        assertThatCode(() -> limiter.checkNotBlocked("2.2.2.2")).doesNotThrowAnyException();

        advanceMinutes(9);
        assertThatThrownBy(() -> limiter.checkNotBlocked("1.1.1.1")).isInstanceOf(RateLimitExceededException.class);
        advanceMinutes(2);
        assertThatCode(() -> limiter.checkNotBlocked("1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Chequear no cuenta: solo los fallos cuentan, así los requests legítimos no consumen el cupo")
    void checkingDoesNotCount() {
        for (int i = 0; i < 500; i++) {
            limiter.checkNotBlocked("3.3.3.3");
        }
        assertThatCode(() -> limiter.checkNotBlocked("3.3.3.3")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("60 borrados por minuto por token: el 61 da 429, otro token no se ve afectado y al minuto se renueva")
    void deletesAreCappedPerTokenPerMinute() {
        for (int i = 0; i < 60; i++) {
            limiter.checkDeleteAllowed("token-a");
        }
        assertThatThrownBy(() -> limiter.checkDeleteAllowed("token-a")).isInstanceOf(RateLimitExceededException.class);
        assertThatCode(() -> limiter.checkDeleteAllowed("token-b")).doesNotThrowAnyException();

        advanceMinutes(2);
        assertThatCode(() -> limiter.checkDeleteAllowed("token-a")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Los registros EXPIRAN: pasada la ventana no queda ninguna clave en memoria")
    void entriesAreEvictedAfterTheirWindow() {
        for (int i = 0; i < 1000; i++) {
            limiter.recordInvalidAttempt("10.0." + (i / 250) + "." + (i % 250));
            limiter.checkDeleteAllowed("token-" + i);
        }
        assertThat(limiter.trackedKeys()).isEqualTo(2000);
        advanceMinutes(11);
        assertThat(limiter.trackedKeys()).isZero();
    }
}
