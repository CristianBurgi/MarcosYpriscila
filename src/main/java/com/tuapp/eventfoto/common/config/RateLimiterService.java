package com.tuapp.eventfoto.common.config;

import com.tuapp.eventfoto.common.exception.RateLimitExceededException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
public class RateLimiterService {

    private static final int UPLOAD_URL_LIMIT_PER_MINUTE = 30;
    private static final int COMMENT_MESSAGE_LIMIT_PER_MINUTE = 15;
    private static final int ADMIN_LOGIN_LIMIT_PER_15_MINUTES = 5;

    private static final long ONE_MINUTE_IN_MS = 60_000L;
    private static final long FIFTEEN_MINUTES_IN_MS = 15 * 60 * 1000L;

    private final Map<String, Queue<Long>> uploadUrlBuckets = new ConcurrentHashMap<>();
    private final Map<String, Queue<Long>> commentMessageBuckets = new ConcurrentHashMap<>();
    private final Map<String, Queue<Long>> adminLoginBuckets = new ConcurrentHashMap<>();

    public void checkUploadUrlRateLimit(String clientIp) {
        checkRateLimit(clientIp, uploadUrlBuckets, UPLOAD_URL_LIMIT_PER_MINUTE, ONE_MINUTE_IN_MS,
                "Has superado el límite de 5 solicitudes de Presigned URL por minuto. Por favor aguardá unos segundos.");
    }

    public void checkCommentMessageRateLimit(String clientIp) {
        checkRateLimit(clientIp, commentMessageBuckets, COMMENT_MESSAGE_LIMIT_PER_MINUTE, ONE_MINUTE_IN_MS,
                "Has superado el límite de 3 envíos de comentarios/mensajes por minuto. Por favor aguardá unos segundos.");
    }

    public void checkAdminLoginRateLimit(String clientIp) {
        checkRateLimit(clientIp, adminLoginBuckets, ADMIN_LOGIN_LIMIT_PER_15_MINUTES, FIFTEEN_MINUTES_IN_MS,
                "Has superado el límite de 5 intentos de inicio de sesión en 15 minutos. Por favor intentá más tarde.");
    }

    private void checkRateLimit(String clientIp, Map<String, Queue<Long>> buckets, int maxLimit, long windowMs, String errorMessage) {
        String ip = (clientIp != null && !clientIp.isBlank()) ? clientIp : "unknown-ip";
        long now = System.currentTimeMillis();

        Queue<Long> timestamps = buckets.computeIfAbsent(ip, k -> new ConcurrentLinkedQueue<>());

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
        uploadUrlBuckets.clear();
        commentMessageBuckets.clear();
        adminLoginBuckets.clear();
    }
}

