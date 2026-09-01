package com.tuapp.eventfoto.common.config;

import com.tuapp.eventfoto.common.exception.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Equivalente al test de rate limiting de fotos, pero para comentarios/mensajes:
 * muchos invitados desde la MISMA IP, y se confirma que el límite de 15/min es
 * por guestToken (no un pozo compartido entre todos).
 *
 * Nota sobre los números: la capa por IP para comentarios/mensajes es 100/min, así
 * que para aislar la capa por guestToken se simulan tantos invitados como caben bajo
 * ese techo. La capa por IP se verifica aparte, con invitados de un solo mensaje.
 */
class CommentMessageRateLimitTest {

    private static final String SHARED_IP = "203.0.113.7"; // todos los invitados del salón salen por acá
    private static final int PER_GUEST_LIMIT = 15;
    private static final int PER_IP_LIMIT = 100;

    private RateLimiterService rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new RateLimiterService();
        rateLimiter.resetRateLimits();
    }

    @Test
    void cadaGuestTokenTieneSuPropioLimiteDe15_noCompartido() {
        int guests = 6; // 6 * 15 = 90 requests exitosas < 100 (techo por IP)

        for (int g = 0; g < guests; g++) {
            String guestToken = "guest-" + g;

            // Los primeros 15 pasan
            for (int i = 0; i < PER_GUEST_LIMIT; i++) {
                int finalI = i;
                assertDoesNotThrow(
                        () -> rateLimiter.checkCommentMessageRateLimit(SHARED_IP, guestToken),
                        "guest " + guestToken + " request #" + (finalI + 1) + " no debería bloquearse");
            }

            // El 16 de ESTE invitado se bloquea por su propia capa (no por la de IP:
            // recién vamos por g*15 requests de IP acumuladas)
            RateLimitExceededException ex = assertThrows(RateLimitExceededException.class,
                    () -> rateLimiter.checkCommentMessageRateLimit(SHARED_IP, guestToken));
            assertTrue(ex.getMessage().toLowerCase().contains("comentario") || ex.getMessage().toLowerCase().contains("mensaje"),
                    "El mensaje de error debe ser el de la capa por invitado, no el global de IP");
        }

        // Un invitado NUEVO desde la misma IP todavía puede comentar: los 6 anteriores
        // agotando su cupo no consumieron el suyo (buckets independientes por token).
        assertDoesNotThrow(() -> rateLimiter.checkCommentMessageRateLimit(SHARED_IP, "guest-recien-llegado"),
                "El límite de un invitado NO debe restarle cupo a otro");
    }

    @Test
    void laCapaPorIpSigueActivaComoDefensaGlobal() {
        // 200 invitados distintos (uno por token) => la capa por guestToken nunca se
        // dispara; el único freno posible es la capa por IP (100/min).
        int allowed = 0;
        int blocked = 0;
        for (int g = 0; g < 200; g++) {
            try {
                rateLimiter.checkCommentMessageRateLimit(SHARED_IP, "solo-un-mensaje-" + g);
                allowed++;
            } catch (RateLimitExceededException e) {
                blocked++;
                assertTrue(e.getMessage().toLowerCase().contains("ip"),
                        "A partir del request 101 el bloqueo debe venir de la capa por IP");
            }
        }
        assertEquals(PER_IP_LIMIT, allowed, "La capa por IP debe dejar pasar exactamente 100/min");
        assertEquals(100, blocked);
    }

    @Test
    void distintaIpNoSeAfectaEntreInvitados() {
        String guestToken = "guest-movil-datos";
        // Agota el cupo del invitado desde la IP del salón
        for (int i = 0; i < PER_GUEST_LIMIT; i++) {
            rateLimiter.checkCommentMessageRateLimit(SHARED_IP, guestToken);
        }
        assertThrows(RateLimitExceededException.class,
                () -> rateLimiter.checkCommentMessageRateLimit(SHARED_IP, guestToken));

        // El bucket es por token, así que el mismo token desde otra IP tampoco tiene
        // cupo (identidad del invitado), pero un token distinto desde otra IP sí.
        assertThrows(RateLimitExceededException.class,
                () -> rateLimiter.checkCommentMessageRateLimit("198.51.100.42", guestToken));
        assertDoesNotThrow(
                () -> rateLimiter.checkCommentMessageRateLimit("198.51.100.42", "otro-guest-otra-ip"));
    }
}
