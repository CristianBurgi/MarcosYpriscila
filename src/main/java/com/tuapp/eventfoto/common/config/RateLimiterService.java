package com.tuapp.eventfoto.common.config;

import com.tuapp.eventfoto.common.exception.RateLimitExceededException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
public class RateLimiterService {

    private static final int UPLOAD_URL_LIMIT_PER_MINUTE = 5;
    private static final int COMMENT_MESSAGE_LIMIT_PER_MINUTE = 3;
    private static final long ONE_MINUTE_IN_MS = 60_000L;

    private final Map<String, Queue<Long>> uploadUrlBuckets = new ConcurrentHashMap<>();
    private final Map<String, Queue<Long>> commentMessageBuckets = new ConcurrentHashMap<>();

    public void checkUploadUrlRateLimit(String clientIp) {
        checkRateLimit(clientIp, uploadUrlBuckets, UPLOAD_URL_LIMIT_PER_MINUTE,
                "Has superado el límite de 5 solicitudes de Presigned URL por minuto. Por favor aguardá unos segundos.");
    }

    public void checkCommentMessageRateLimit(String clientIp) {
        checkRateLimit(clientIp, commentMessageBuckets, COMMENT_MESSAGE_LIMIT_PER_MINUTE,
                "Has superado el límite de 3 envíos de comentarios/mensajes por minuto. Por favor aguardá unos segundos.");
    }

    private void checkRateLimit(String clientIp, Map<String, Queue<Long>> buckets, int maxLimit, String errorMessage) {
        String ip = (clientIp != null && !clientIp.isBlank()) ? clientIp : "unknown-ip";
        long now = System.currentTimeMillis();

        Queue<Long> timestamps = buckets.computeIfAbsent(ip, k -> new ConcurrentLinkedQueue<>());

        // Remover marcas de tiempo anteriores a 1 minuto
        while (!timestamps.isEmpty() && (now - timestamps.peek() > ONE_MINUTE_IN_MS)) {
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
    }
}
