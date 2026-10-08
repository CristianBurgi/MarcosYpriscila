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

    /**
     * Canal de una demo (Fase 9.7-B): la clave del mapa es un UUID para un evento o un DemoChannel para una demo. Al
     * ser de otro tipo, el canal de una demo nunca puede coincidir con el de un evento, ni con el de otra demo. El
     * toString recorta el sid: los logs no lo muestran entero (la URL de la demo es la llave).
     */
    public record DemoChannel(String sid) {
        @Override
        public String toString() {
            return "demo:" + sid.substring(0, Math.min(6, sid.length()));
        }
    }

    private final Map<Object, List<SseEmitter>> eventEmitters = new ConcurrentHashMap<>();

    /**
     * Suscribe un cliente al flujo Server-Sent Events (SSE) para un evento específico.
     */
    public SseEmitter subscribe(UUID eventId) {
        return register(eventId, new SseEmitter(SSE_TIMEOUT_MS));
    }

    /** Suscribe la pantalla de UNA demo: solo recibe lo que se publica en su propio canal. */
    public SseEmitter subscribeDemo(String sid) {
        return register(new DemoChannel(sid), new SseEmitter(SSE_TIMEOUT_MS));
    }

    public void broadcastDemoPhoto(String sid, PhotoResponseDTO photo) {
        broadcast(new DemoChannel(sid), "PHOTO_PUBLISHED", SseNotificationEvent.of("PHOTO_PUBLISHED", photo));
    }

    public void broadcastDemoPhotoDeleted(String sid, UUID photoId) {
        broadcast(new DemoChannel(sid), "PHOTO_DELETED", SseNotificationEvent.of("PHOTO_DELETED", Map.of("photoId", photoId)));
    }

    public void broadcastDemoMessage(String sid, MessageResponseDTO message) {
        broadcast(new DemoChannel(sid), "MESSAGE_CREATED", SseNotificationEvent.of("MESSAGE_CREATED", message));
    }

    /** Cierra las pantallas abiertas de una demo borrada: al reconectar reciben 404 y vuelven a /demo. */
    public void closeDemo(String sid) {
        List<SseEmitter> emitters = eventEmitters.remove(new DemoChannel(sid));
        if (emitters != null) {
            emitters.forEach(emitter -> {
                try {
                    emitter.complete();
                } catch (Exception e) {
                    log.debug("Canal SSE de demo ya cerrado: {}", e.toString());
                }
            });
        }
    }

    /**
     * Registra un emitter ya creado en la lista activa del evento. Package-private para
     * poder testear el manejo de desconexiones con emitters simulados.
     */
    SseEmitter register(Object eventId, SseEmitter emitter) {
        // Alta ATÓMICA: agregar el emitter y (si hace falta) crear la lista ocurren dentro de un solo
        // compute() sobre la clave del evento. Antes era computeIfAbsent + add, y la limpieza hacía
        // remove + isEmpty + remove(eventId) por separado: un cliente que se suscribía en esa
        // ventana recibía la lista que la limpieza estaba por quitar del mapa y quedaba huérfano,
        // sin recibir NUNCA un evento (pasaba con reconexiones masivas, ej. un salón que pierde señal).
        List<SseEmitter> emitters = eventEmitters.compute(eventId, (id, current) -> {
            List<SseEmitter> list = current != null ? current : new CopyOnWriteArrayList<>();
            list.add(emitter);
            return list;
        });

        log.info("Nuevo cliente suscripto a SSE para evento ID: {}. Suscriptores activos para este evento: {}", eventId, emitters.size());

        // Limpieza automática al finalizar, expirar o fallar la conexión
        Runnable cleanup = () -> {
            removeEmitter(eventId, emitter);
            log.debug("Conexión SSE cerrada/removida para evento ID: {}. Suscriptores restantes: {}", eventId, getActiveSubscribersCount(eventId));
        };

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> {
            log.debug("Canal SSE cerrado con error para evento ID {}: {}", eventId, e.getMessage());
            cleanup.run();
        });

        // Evento inicial de confirmación de conexión
        try {
            emitter.send(SseEmitter.event()
                    .name("INIT")
                    .data("Conexión exitosa a la transmisión en vivo del evento"));
        } catch (IOException e) {
            log.debug("Cliente SSE desconectado antes del mensaje INIT: {}", e.getMessage());
            removeEmitter(eventId, emitter);
        }

        return emitter;
    }

    /** Baja ATÓMICA: quita el emitter y, si la lista queda vacía, la quita del mapa en el mismo paso. */
    private void removeEmitter(Object eventId, SseEmitter emitter) {
        eventEmitters.computeIfPresent(eventId, (id, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }

    /**
     * Transmite una fotografía recién publicada (la publicación es automática al confirmar
     * la subida) a todos los suscriptores del evento: álbum, pantalla del salón y panel.
     */
    public void broadcastPhotoPublished(UUID eventId, PhotoResponseDTO photo) {
        SseNotificationEvent notification = SseNotificationEvent.of("PHOTO_PUBLISHED", photo);
        broadcast(eventId, "PHOTO_PUBLISHED", notification);
    }

    /**
     * Transmite la eliminación de una fotografía para que el álbum y la pantalla la saquen al instante.
     */
    public void broadcastPhotoDeleted(UUID eventId, UUID photoId) {
        Map<String, Object> data = Map.of("photoId", photoId);
        SseNotificationEvent notification = SseNotificationEvent.of("PHOTO_DELETED", data);
        broadcast(eventId, "PHOTO_DELETED", notification);
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

    private void broadcast(Object eventId, String eventName, Object data) {
        List<SseEmitter> emitters = eventEmitters.get(eventId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        log.info("Emitiendo evento SSE '{}' a {} clientes para evento ID: {}", eventName, emitters.size(), eventId);

        for (SseEmitter emitter : emitters) {
            safeSend(emitters, emitter, SseEmitter.event().name(eventName).data(data));
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
                safeSend(emitters, emitter, SseEmitter.event().comment("ping"));
            }
        });
    }

    /**
     * Escribe a UN emitter sin que su falla afecte al resto: se remueve de la lista activa
     * y se sigue. Nunca relanza.
     *
     * Fase 9.0: en la boda del 19/09 Sentry registró 217 eventos "IOException: Broken pipe"
     * desde acá. Un invitado que cierra el álbum o un celular que pierde señal es una
     * desconexión NORMAL, no un error: se loguea en debug. Ojo: ese ruido no venía de
     * este catch (ya existía) sino del async error dispatch que Spring dispara al fallar
     * la escritura -- eso se silencia en GlobalExceptionHandler#handleClientDisconnected.
     */
    private void safeSend(List<SseEmitter> emitters, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException e) {
            log.debug("Cliente SSE desconectado, removiendo emisor: {}", e.getMessage());
            emitters.remove(emitter);
        } catch (Exception e) {
            // Ej.: IllegalStateException si el emitter ya estaba completado.
            log.warn("Fallo inesperado al emitir SSE a un cliente, removiendo emisor: {}", e.toString());
            emitters.remove(emitter);
        }
    }

    public int getActiveSubscribersCount(Object eventId) {
        List<SseEmitter> emitters = eventEmitters.get(eventId);
        return emitters != null ? emitters.size() : 0;
    }
}
