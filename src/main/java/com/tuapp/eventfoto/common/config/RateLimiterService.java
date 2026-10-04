package com.tuapp.eventfoto.common.config;

import com.tuapp.eventfoto.common.exception.RateLimitExceededException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Rate limiter en dos capas para endpoints de invitados:
 * 1. Capa primaria: por guestToken (identidad del invitado, 30 req/min)
 *    - NOTA: guestToken es client-controlled (generado en el navegador)
 *    - NO es defensa contra atacantes decididos, es para UX normal
 * 2. Capa secundaria: por IP (defensa contra spam/bots, 500 req/min)
 *    - Se activa solo en caso de ataque masivo
 *    - No debería activarse en uso normal del evento
 *
 * El admin login rate limit sigue siendo solo por IP (no aplica guestToken).
 */
@Service
public class RateLimiterService {

    // === CAPAS DE RATE LIMIT PARA UPLOAD (invitados) ===
    // Capa primaria: por guestToken (identidad del invitado)
    private static final int UPLOAD_URL_LIMIT_PER_GUESTTOKEN_PER_MINUTE = 30;
    // Capa secundaria: por IP (defensa contra automatización)
    private static final int UPLOAD_URL_LIMIT_PER_IP_PER_MINUTE = 500;

    // === CAPAS DE RATE LIMIT PARA COMMENTS/MESSAGES ===
    private static final int COMMENT_MESSAGE_LIMIT_PER_GUESTTOKEN_PER_MINUTE = 15;
    private static final int COMMENT_MESSAGE_LIMIT_PER_IP_PER_MINUTE = 100;

    // === RATE LIMIT PARA ADMIN (sin guestToken) ===
    private static final int ADMIN_LOGIN_LIMIT_PER_15_MINUTES = 5;

    // === CHECKOUT (fase 9.3): buckets propios, no comparten cupo con el login ===
    private static final int CHECKOUT_LIMIT_PER_15_MINUTES = 10;

    private static final long ONE_MINUTE_IN_MS = 60_000L;
    private static final long FIFTEEN_MINUTES_IN_MS = 15 * 60 * 1000L;

    // Buckets para upload URLs (dos claves: guestToken e IP)
    private final Map<String, Queue<Long>> uploadUrlByGuestTokenBuckets = new ConcurrentHashMap<>();
    private final Map<String, Queue<Long>> uploadUrlByIpBuckets = new ConcurrentHashMap<>();

    // Buckets de upload-direct (multipart por el servidor): mismos valores que upload-url, buckets propios
    private final Map<String, Queue<Long>> uploadDirectByGuestTokenBuckets = new ConcurrentHashMap<>();
    private final Map<String, Queue<Long>> uploadDirectByIpBuckets = new ConcurrentHashMap<>();

    // Buckets para comments/messages (dos claves: guestToken e IP)
    private final Map<String, Queue<Long>> commentMessageByGuestTokenBuckets = new ConcurrentHashMap<>();
    private final Map<String, Queue<Long>> commentMessageByIpBuckets = new ConcurrentHashMap<>();

    // Buckets para admin login (solo IP, sin guestToken)
    private final Map<String, Queue<Long>> adminLoginBuckets = new ConcurrentHashMap<>();

    // Bucket separado para login de superadmin (mismo mecanismo, aislado del de organizador)
    private final Map<String, Queue<Long>> superadminLoginBuckets = new ConcurrentHashMap<>();

    // Checkout público (por IP) y recompra del organizador (por organizerId)
    private final Map<String, Queue<Long>> checkoutBuckets = new ConcurrentHashMap<>();
    private final Map<String, Queue<Long>> adminCheckoutBuckets = new ConcurrentHashMap<>();

    /**
     * Chequea rate limit para presigned URLs de foto.
     * Aplica dos capas: primero por guestToken, luego por IP.
     */
    public void checkUploadUrlRateLimit(String clientIp, String guestToken) {
        // Capa primaria: por guestToken (cada invitado individual)
        checkRateLimit(guestToken, uploadUrlByGuestTokenBuckets,
                UPLOAD_URL_LIMIT_PER_GUESTTOKEN_PER_MINUTE, ONE_MINUTE_IN_MS,
                "Has superado el límite de 30 solicitudes de Presigned URL por minuto. Por favor aguardá unos segundos.");

        // Capa secundaria: por IP (defensa contra automatización masiva)
        checkRateLimit(clientIp, uploadUrlByIpBuckets,
                UPLOAD_URL_LIMIT_PER_IP_PER_MINUTE, ONE_MINUTE_IN_MS,
                "Has superado el límite global de solicitudes desde tu IP. Por favor aguardá unos minutos.");
    }

    /**
     * Rate limit de upload-direct: no pasa por una presigned URL, así que sin esto era el único camino de subida sin
     * freno. Mismos valores que upload-url (30/min por guestToken, 500/min por IP) y buckets propios, para que el
     * fallback de un invitado no le gaste el cupo de presigned URLs.
     */
    public void checkUploadDirectRateLimit(String clientIp, String guestToken) {
        checkRateLimit(guestToken, uploadDirectByGuestTokenBuckets,
                UPLOAD_URL_LIMIT_PER_GUESTTOKEN_PER_MINUTE, ONE_MINUTE_IN_MS,
                "Has superado el límite de 30 subidas por minuto. Por favor aguardá unos segundos.");

        checkRateLimit(clientIp, uploadDirectByIpBuckets,
                UPLOAD_URL_LIMIT_PER_IP_PER_MINUTE, ONE_MINUTE_IN_MS,
                "Has superado el límite global de subidas desde tu IP. Por favor aguardá unos minutos.");
    }

    /**
     * Chequea rate limit para comentarios/mensajes.
     * Aplica dos capas: primero por guestToken, luego por IP.
     */
    public void checkCommentMessageRateLimit(String clientIp, String guestToken) {
        // Capa primaria: por guestToken
        checkRateLimit(guestToken, commentMessageByGuestTokenBuckets,
                COMMENT_MESSAGE_LIMIT_PER_GUESTTOKEN_PER_MINUTE, ONE_MINUTE_IN_MS,
                "Has superado el límite de comentarios/mensajes por minuto. Por favor aguardá unos segundos.");

        // Capa secundaria: por IP
        checkRateLimit(clientIp, commentMessageByIpBuckets,
                COMMENT_MESSAGE_LIMIT_PER_IP_PER_MINUTE, ONE_MINUTE_IN_MS,
                "Has superado el límite global de comentarios desde tu IP. Por favor aguardá unos minutos.");
    }

    /**
     * Chequea rate limit para login de admin.
     * Solo por IP, sin guestToken (correctamente asegurado).
     */
    public void checkAdminLoginRateLimit(String clientIp) {
        checkRateLimit(clientIp, adminLoginBuckets, ADMIN_LOGIN_LIMIT_PER_15_MINUTES, FIFTEEN_MINUTES_IN_MS,
                "Has superado el límite de 5 intentos de inicio de sesión en 15 minutos. Por favor intentá más tarde.");
    }

    /**
     * Chequea rate limit para login de superadmin. Mismo mecanismo que el de
     * organizador, pero con su propio bucket -- no comparten cupo de intentos.
     */
    public void checkSuperadminLoginRateLimit(String clientIp) {
        checkRateLimit(clientIp, superadminLoginBuckets, ADMIN_LOGIN_LIMIT_PER_15_MINUTES, FIFTEEN_MINUTES_IN_MS,
                "Has superado el límite de 5 intentos de inicio de sesión en 15 minutos. Por favor intentá más tarde.");
    }

    /** POST /api/v1/checkout (público): 10 intentos por IP cada 15 minutos. */
    public void checkCheckoutRateLimit(String clientIp) {
        checkRateLimit(clientIp, checkoutBuckets, CHECKOUT_LIMIT_PER_15_MINUTES, FIFTEEN_MINUTES_IN_MS,
                "Hiciste demasiados intentos de compra en poco tiempo. Por favor probá de nuevo en unos minutos.");
    }

    /** POST /api/v1/admin/checkout: 10 intentos por organizador cada 15 minutos. */
    public void checkAdminCheckoutRateLimit(String organizerId) {
        checkRateLimit(organizerId, adminCheckoutBuckets, CHECKOUT_LIMIT_PER_15_MINUTES, FIFTEEN_MINUTES_IN_MS,
                "Hiciste demasiados intentos de compra en poco tiempo. Por favor probá de nuevo en unos minutos.");
    }

    // === DEPRECATED: Métodos antiguos que solo usan IP ===
    // Mantenerlos para compatibilidad si algo aún los usa
    @Deprecated
    public void checkUploadUrlRateLimit(String clientIp) {
        // Usar el nuevo método que requiere guestToken
        checkUploadUrlRateLimit(clientIp, "unknown-guest");
    }

    @Deprecated
    public void checkCommentMessageRateLimit(String clientIp) {
        // Usar el nuevo método que requiere guestToken
        checkCommentMessageRateLimit(clientIp, "unknown-guest");
    }

    private void checkRateLimit(String key, Map<String, Queue<Long>> buckets, int maxLimit, long windowMs, String errorMessage) {
        String normalizedKey = (key != null && !key.isBlank()) ? key : "unknown";
        long now = System.currentTimeMillis();

        Queue<Long> timestamps = buckets.computeIfAbsent(normalizedKey, k -> new ConcurrentLinkedQueue<>());

        // Remover marcas de tiempo anteriores a la ventana definida
        while (!timestamps.isEmpty() && (now - timestamps.peek() > windowMs)) {
            timestamps.poll();
        }

        if (timestamps.size() >= maxLimit) {
            throw new RateLimitExceededException(errorMessage);
        }

        timestamps.add(now);
    }

    public void resetRateLimits() {
        uploadUrlByGuestTokenBuckets.clear();
        uploadUrlByIpBuckets.clear();
        uploadDirectByGuestTokenBuckets.clear();
        uploadDirectByIpBuckets.clear();
        commentMessageByGuestTokenBuckets.clear();
        commentMessageByIpBuckets.clear();
        adminLoginBuckets.clear();
        superadminLoginBuckets.clear();
        checkoutBuckets.clear();
        adminCheckoutBuckets.clear();
    }
}

