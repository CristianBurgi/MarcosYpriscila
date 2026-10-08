package com.tuapp.eventfoto.moderation;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.tuapp.eventfoto.common.exception.RateLimitExceededException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Límites del lado del moderador, con estructuras que EXPIRAN solas (Caffeine): los registros viejos se
 * descartan y el tamaño está acotado.
 *
 * <ul>
 *   <li>Intentos inválidos: 20 fallos (token inexistente o mal formado) cada 10 minutos por IP. Cuenta FALLOS,
 *       no requests: un moderador legítimo detrás de un WiFi compartido no consume el cupo. Con el cupo agotado la
 *       IP recibe 429 en toda ruta del moderador, aun con un token válido, hasta que expira la ventana.</li>
 *   <li>Borrados: 60 por minuto por token (cuentan las requests de borrado, salgan bien o mal).</li>
 * </ul>
 * Las ventanas son fijas, contadas desde el primer evento (expireAfterWrite sobre un contador mutable).
 */
@Component
public class ModeratorRateLimiter {

    public static final int MAX_INVALID_ATTEMPTS = 20;
    public static final Duration INVALID_ATTEMPTS_WINDOW = Duration.ofMinutes(10);
    public static final int MAX_DELETES = 60;
    public static final Duration DELETES_WINDOW = Duration.ofMinutes(1);
    private static final long MAX_TRACKED_KEYS = 100_000;

    private final Cache<String, AtomicInteger> invalidAttemptsByIp;
    private final Cache<String, AtomicInteger> deletesByToken;

    public ModeratorRateLimiter() {
        this(Ticker.systemTicker());
    }

    /** Con reloj inyectable, para probar la expiración sin dormir. */
    ModeratorRateLimiter(Ticker ticker) {
        this.invalidAttemptsByIp = Caffeine.newBuilder().ticker(ticker)
                .expireAfterWrite(INVALID_ATTEMPTS_WINDOW).maximumSize(MAX_TRACKED_KEYS).build();
        this.deletesByToken = Caffeine.newBuilder().ticker(ticker)
                .expireAfterWrite(DELETES_WINDOW).maximumSize(MAX_TRACKED_KEYS).build();
    }

    /** 429 si la IP ya agotó su cupo de intentos inválidos. No cuenta nada. */
    public void checkNotBlocked(String clientIp) {
        AtomicInteger failures = invalidAttemptsByIp.getIfPresent(key(clientIp));
        if (failures != null && failures.get() >= MAX_INVALID_ATTEMPTS) {
            throw new RateLimitExceededException("Demasiados intentos. Probá de nuevo en unos minutos.");
        }
    }

    public void recordInvalidAttempt(String clientIp) {
        invalidAttemptsByIp.get(key(clientIp), k -> new AtomicInteger()).incrementAndGet();
    }

    /** Cuenta un borrado del token y responde 429 si supera 60 en la ventana. */
    public void checkDeleteAllowed(String moderatorToken) {
        int used = deletesByToken.get(moderatorToken, k -> new AtomicInteger()).incrementAndGet();
        if (used > MAX_DELETES) {
            throw new RateLimitExceededException("Demasiados borrados seguidos. Esperá un minuto e intentá de nuevo.");
        }
    }

    /** Para los tests. */
    public void reset() {
        invalidAttemptsByIp.invalidateAll();
        deletesByToken.invalidateAll();
    }

    long trackedKeys() {
        invalidAttemptsByIp.cleanUp();
        deletesByToken.cleanUp();
        return invalidAttemptsByIp.estimatedSize() + deletesByToken.estimatedSize();
    }

    private static String key(String clientIp) {
        return clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
    }
}
