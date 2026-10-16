package com.tuapp.eventfoto.common.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tuapp.eventfoto.common.exception.RateLimitExceededException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

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
 *
 * Cada bucket es un Caffeine que expira la clave una ventana después de su último pedido y guarda como mucho
 * 100.000 claves, así que rotar tokens o IPs no hace crecer la memoria sin fin.
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

    // === RECUPERACIÓN DE CONTRASEÑA (9.2-B) ===
    private static final int PASSWORD_RESET_REQUEST_LIMIT_PER_IP_PER_15_MINUTES = 5;
    private static final int PASSWORD_RESET_REQUEST_LIMIT_PER_EMAIL_PER_HOUR = 3;
    private static final int PASSWORD_RESET_CONFIRM_LIMIT_PER_IP_PER_15_MINUTES = 10;
    private static final String PASSWORD_RESET_LIMIT_MESSAGE = "Hiciste demasiados pedidos en poco tiempo. Esperá un rato y probá de nuevo.";
    // Página de retorno (fase 9.4): confirma y consulta cada 3 s durante 90 s (~30 rondas, 20/min); 40/min deja margen
    // para dos pestañas o un "Volver a consultar".
    private static final int CHECKOUT_RETURN_LIMIT_PER_MINUTE = 40;

    // === BOTÓN DE ARREPENTIMIENTO: cada envío manda dos mails ===
    private static final int WITHDRAWAL_LIMIT_PER_IP_PER_15_MINUTES = 5;
    // Recibo al email que escribió la persona: uno por hora, para que el formulario no sirva para llenar una casilla ajena.
    private static final int WITHDRAWAL_RECEIPT_LIMIT_PER_EMAIL_PER_HOUR = 1;

    // === DEMO (fase 9.7-B), por IP: más estricto que un evento real, pero con margen para el CGNAT de las redes
    // móviles (muchos celulares salen por la misma IP). Tope por IP: 20 demos x 5 fotos = 100 fotos por hora.
    private static final int DEMO_UPLOAD_LIMIT_PER_IP_PER_MINUTE = 10;
    private static final int DEMO_SESSION_LIMIT_PER_IP_PER_HOUR = 20;
    private static final long ONE_HOUR_IN_MS = 60 * 60 * 1000L;

    private static final long ONE_MINUTE_IN_MS = 60_000L;
    private static final long FIFTEEN_MINUTES_IN_MS = 15 * 60 * 1000L;
    private static final long MAX_TRACKED_KEYS = 100_000;

    // Buckets para upload URLs (dos claves: guestToken e IP)
    private final Cache<String, Deque<Long>> uploadUrlByGuestTokenBuckets = buckets(ONE_MINUTE_IN_MS);
    private final Cache<String, Deque<Long>> uploadUrlByIpBuckets = buckets(ONE_MINUTE_IN_MS);

    // Buckets de upload-direct (multipart por el servidor): mismos valores que upload-url, buckets propios
    private final Cache<String, Deque<Long>> uploadDirectByGuestTokenBuckets = buckets(ONE_MINUTE_IN_MS);
    private final Cache<String, Deque<Long>> uploadDirectByIpBuckets = buckets(ONE_MINUTE_IN_MS);

    // Buckets para comments/messages (dos claves: guestToken e IP)
    private final Cache<String, Deque<Long>> commentMessageByGuestTokenBuckets = buckets(ONE_MINUTE_IN_MS);
    private final Cache<String, Deque<Long>> commentMessageByIpBuckets = buckets(ONE_MINUTE_IN_MS);

    // Buckets para admin login (solo IP, sin guestToken)
    private final Cache<String, Deque<Long>> adminLoginBuckets = buckets(FIFTEEN_MINUTES_IN_MS);

    // Bucket separado para login de superadmin (mismo mecanismo, aislado del de organizador)
    private final Cache<String, Deque<Long>> superadminLoginBuckets = buckets(FIFTEEN_MINUTES_IN_MS);

    // Checkout público (por IP) y recompra del organizador (por organizerId)
    private final Cache<String, Deque<Long>> checkoutBuckets = buckets(FIFTEEN_MINUTES_IN_MS);
    private final Cache<String, Deque<Long>> adminCheckoutBuckets = buckets(FIFTEEN_MINUTES_IN_MS);
    // Página de retorno: confirmar el pago y consultar el estado, cada uno con su bucket por IP
    private final Cache<String, Deque<Long>> checkoutConfirmBuckets = buckets(ONE_MINUTE_IN_MS);
    private final Cache<String, Deque<Long>> checkoutStatusBuckets = buckets(ONE_MINUTE_IN_MS);
    // Demo: subidas y sesiones nuevas, por IP
    private final Cache<String, Deque<Long>> passwordResetRequestByIpBuckets = buckets(FIFTEEN_MINUTES_IN_MS);
    private final Cache<String, Deque<Long>> passwordResetRequestByEmailBuckets = buckets(ONE_HOUR_IN_MS);
    private final Cache<String, Deque<Long>> passwordResetConfirmBuckets = buckets(FIFTEEN_MINUTES_IN_MS);
    private final Cache<String, Deque<Long>> withdrawalBuckets = buckets(FIFTEEN_MINUTES_IN_MS);
    private final Cache<String, Deque<Long>> withdrawalReceiptBuckets = buckets(ONE_HOUR_IN_MS);

    private final Cache<String, Deque<Long>> demoUploadBuckets = buckets(ONE_MINUTE_IN_MS);
    private final Cache<String, Deque<Long>> demoSessionBuckets = buckets(ONE_HOUR_IN_MS);

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

    /**
     * Pedido de recuperación de contraseña: 5 por IP cada 15 minutos y 3 por email por hora. El mensaje es el mismo
     * exista o no la cuenta (el cupo por email se gasta igual para un email inexistente): no revela nada.
     */
    public void checkPasswordResetRequestRateLimit(String clientIp, String normalizedEmail) {
        checkRateLimit(clientIp, passwordResetRequestByIpBuckets, PASSWORD_RESET_REQUEST_LIMIT_PER_IP_PER_15_MINUTES,
                FIFTEEN_MINUTES_IN_MS, PASSWORD_RESET_LIMIT_MESSAGE);
        checkRateLimit(normalizedEmail, passwordResetRequestByEmailBuckets, PASSWORD_RESET_REQUEST_LIMIT_PER_EMAIL_PER_HOUR,
                ONE_HOUR_IN_MS, PASSWORD_RESET_LIMIT_MESSAGE);
    }

    /** Confirmación de la contraseña nueva con el link: 10 por IP cada 15 minutos. */
    public void checkPasswordResetConfirmRateLimit(String clientIp) {
        checkRateLimit(clientIp, passwordResetConfirmBuckets, PASSWORD_RESET_CONFIRM_LIMIT_PER_IP_PER_15_MINUTES,
                FIFTEEN_MINUTES_IN_MS, PASSWORD_RESET_LIMIT_MESSAGE);
    }

    /** POST /arrepentimiento (público): 5 envíos por IP cada 15 minutos. */
    public void checkWithdrawalRateLimit(String clientIp) {
        checkRateLimit(clientIp, withdrawalBuckets, WITHDRAWAL_LIMIT_PER_IP_PER_15_MINUTES, FIFTEEN_MINUTES_IN_MS,
                "Demasiados intentos, probá en unos minutos.");
    }

    /**
     * Recibo del pedido de arrepentimiento: uno por email por hora. No lanza: quien llama guarda el pedido y avisa a
     * EventFoto igual, y solo se saltea el recibo (la persona ve la misma confirmación).
     */
    public boolean allowWithdrawalReceipt(String normalizedEmail) {
        try {
            checkRateLimit(normalizedEmail, withdrawalReceiptBuckets, WITHDRAWAL_RECEIPT_LIMIT_PER_EMAIL_PER_HOUR, ONE_HOUR_IN_MS, "");
            return true;
        } catch (RateLimitExceededException e) {
            return false;
        }
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

    /** POST /api/v1/checkout/confirm (página de retorno): 40 por IP por minuto. */
    public void checkCheckoutConfirmRateLimit(String clientIp) {
        checkRateLimit(clientIp, checkoutConfirmBuckets, CHECKOUT_RETURN_LIMIT_PER_MINUTE, ONE_MINUTE_IN_MS,
                "Demasiadas consultas seguidas. Esperá un momento y volvé a intentar.");
    }

    /** POST /api/v1/checkout/status (página de retorno): 40 por IP por minuto. */
    public void checkCheckoutStatusRateLimit(String clientIp) {
        checkRateLimit(clientIp, checkoutStatusBuckets, CHECKOUT_RETURN_LIMIT_PER_MINUTE, ONE_MINUTE_IN_MS,
                "Demasiadas consultas seguidas. Esperá un momento y volvé a intentar.");
    }

    /** POST /api/v1/demo/{sid}/photos: 10 subidas por IP por minuto, entre todas las demos de esa IP. */
    public void checkDemoUploadRateLimit(String clientIp) {
        checkRateLimit(clientIp, demoUploadBuckets, DEMO_UPLOAD_LIMIT_PER_IP_PER_MINUTE, ONE_MINUTE_IN_MS,
                "Subiste muchas fotos seguidas. Esperá un minuto y probá de nuevo.");
    }

    /** POST /api/v1/demo/sessions: 20 demos nuevas por IP por hora. */
    public void checkDemoSessionRateLimit(String clientIp) {
        checkRateLimit(clientIp, demoSessionBuckets, DEMO_SESSION_LIMIT_PER_IP_PER_HOUR, ONE_HOUR_IN_MS,
                "Creaste muchas demos en poco tiempo. Probá de nuevo en un rato.");
    }

    private static Cache<String, Deque<Long>> buckets(long windowMs) {
        return Caffeine.newBuilder().expireAfterWrite(Duration.ofMillis(windowMs)).maximumSize(MAX_TRACKED_KEYS).build();
    }

    private void checkRateLimit(String key, Cache<String, Deque<Long>> buckets, int maxLimit, long windowMs, String errorMessage) {
        String normalizedKey = (key != null && !key.isBlank()) ? key : "unknown";
        long now = System.currentTimeMillis();
        boolean[] allowed = {false};

        // compute() es atómico por clave: descartar lo vencido, contar y anotar pasan sin que otro pedido se meta.
        buckets.asMap().compute(normalizedKey, (k, timestamps) -> {
            Deque<Long> window = timestamps != null ? timestamps : new ArrayDeque<>();
            while (!window.isEmpty() && now - window.peekFirst() > windowMs) {
                window.pollFirst();
            }
            allowed[0] = window.size() < maxLimit;
            if (allowed[0]) {
                window.addLast(now);
            }
            return window;
        });

        if (!allowed[0]) {
            throw new RateLimitExceededException(errorMessage);
        }
    }

    public void resetRateLimits() {
        uploadUrlByGuestTokenBuckets.invalidateAll();
        uploadUrlByIpBuckets.invalidateAll();
        uploadDirectByGuestTokenBuckets.invalidateAll();
        uploadDirectByIpBuckets.invalidateAll();
        commentMessageByGuestTokenBuckets.invalidateAll();
        commentMessageByIpBuckets.invalidateAll();
        adminLoginBuckets.invalidateAll();
        superadminLoginBuckets.invalidateAll();
        checkoutBuckets.invalidateAll();
        adminCheckoutBuckets.invalidateAll();
        checkoutConfirmBuckets.invalidateAll();
        checkoutStatusBuckets.invalidateAll();
        demoUploadBuckets.invalidateAll();
        demoSessionBuckets.invalidateAll();
        passwordResetRequestByIpBuckets.invalidateAll();
        passwordResetRequestByEmailBuckets.invalidateAll();
        passwordResetConfirmBuckets.invalidateAll();
        withdrawalBuckets.invalidateAll();
        withdrawalReceiptBuckets.invalidateAll();
    }
}

