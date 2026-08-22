package com.tuapp.eventfoto.realtime;

import com.tuapp.eventfoto.message.dto.MessageResponseDTO;
import com.tuapp.eventfoto.photo.dto.PhotoResponseDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
public class SseBroadcaster {

    private static final Long SSE_TIMEOUT_MS = 30 * 60 * 1000L; // 30 minutos

    private final Map<UUID, List<SseEmitter>> eventEmitters = new ConcurrentHashMap<>();

    /**
     * Suscribe un cliente al flujo Server-Sent Events (SSE) para un evento específico.
     */
    public SseEmitter subscribe(UUID eventId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        List<SseEmitter> emitters = eventEmitters.computeIfAbsent(eventId, k -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);

        log.info("Nuevo cliente suscripto a SSE para evento ID: {}. Suscriptores activos para este evento: {}", eventId, emitters.size());

        // Limpieza automática al finalizar, expirar o fallar la conexión
        Runnable cleanup = () -> {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                eventEmitters.remove(eventId);
            }
            log.info("Conexión SSE cerrada/removida para evento ID: {}. Suscriptores restantes: {}", eventId, emitters.size());
        };

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> {
            log.warn("Error en canal SSE para evento ID {}: {}", eventId, e.getMessage());
            cleanup.run();
        });

        // Evento inicial de confirmación de conexión
        try {
            emitter.send(SseEmitter.event()
                    .name("INIT")
                    .data("Conexión exitosa a la transmisión en vivo de la boda"));
        } catch (IOException e) {
            log.warn("Fallo al enviar mensaje INIT de SSE: {}", e.getMessage());
            emitter.completeWithError(e);
        }

        return emitter;
    }

    /**
     * Transmite la notificación de una fotografía aprobada a todos los suscriptores del evento.
     */
    public void broadcastPhotoApproved(UUID eventId, PhotoResponseDTO photo) {
        SseNotificationEvent notification = SseNotificationEvent.of("PHOTO_APPROVED", photo);
        broadcast(eventId, "PHOTO_APPROVED", notification);
    }

    /**
     * Transmite la notificación de una fotografía pendiente de aprobación al panel de administración.
     */
    public void broadcastPhotoPending(UUID eventId, PhotoResponseDTO photo) {
        SseNotificationEvent notification = SseNotificationEvent.of("PHOTO_PENDING", photo);
        broadcast(eventId, "PHOTO_PENDING", notification);
    }

    /**
     * Transmite la notificación de una fotografía rechazada y eliminada.
     */
    public void broadcastPhotoRejected(UUID eventId, UUID photoId) {
        Map<String, Object> data = Map.of("photoId", photoId);
        SseNotificationEvent notification = SseNotificationEvent.of("PHOTO_REJECTED", data);
        broadcast(eventId, "PHOTO_REJECTED", notification);
    }

    /**
     * Transmite la notificación de un nuevo mensaje en el libro de visitas a todos los suscriptores del evento.
     */
    public void broadcastMessageCreated(UUID eventId, MessageResponseDTO message) {
        SseNotificationEvent notification = SseNotificationEvent.of("MESSAGE_CREATED", message);
        broadcast(eventId, "MESSAGE_CREATED", notification);
    }

    /**
     * Transmite la notificación de eliminación de un mensaje del libro de visitas.
     */
    public void broadcastMessageDeleted(UUID eventId, UUID messageId) {
        Map<String, Object> payload = Map.of("id", messageId, "eventId", eventId);
        SseNotificationEvent notification = SseNotificationEvent.of("MESSAGE_DELETED", payload);
        broadcast(eventId, "MESSAGE_DELETED", notification);
    }

    /**
     * Transmite la notificación de eliminación de un comentario de fotografía.
     */
    public void broadcastCommentDeleted(UUID eventId, UUID commentId, UUID photoId) {
        Map<String, Object> payload = Map.of("id", commentId, "photoId", photoId, "eventId", eventId);
        SseNotificationEvent notification = SseNotificationEvent.of("COMMENT_DELETED", payload);
        broadcast(eventId, "COMMENT_DELETED", notification);
    }

    private void broadcast(UUID eventId, String eventName, Object data) {
        List<SseEmitter> emitters = eventEmitters.get(eventId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        log.info("Emitiendo evento SSE '{}' a {} clientes para evento ID: {}", eventName, emitters.size(), eventId);

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(data));
            } catch (Exception e) {
                log.warn("Fallo la emisión SSE a un cliente, removiendo emisor zombie: {}", e.getMessage());
                emitters.remove(emitter);
            }
        }
    }

    /**
     * Envía un pulso heartbeat ': ping' cada 25 segundos a todas las conexiones SSE activas.
     * Esto evita que Railway o los balances de carga corten la conexión por inactividad.
     */
    @Scheduled(fixedRate = 25000)
    public void sendHeartbeat() {
        if (eventEmitters.isEmpty()) {
            return;
        }

        eventEmitters.forEach((eventId, emitters) -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    emitters.remove(emitter);
                }
            }
        });
    }

    public int getActiveSubscribersCount(UUID eventId) {
        List<SseEmitter> emitters = eventEmitters.get(eventId);
        return emitters != null ? emitters.size() : 0;
    }
}
