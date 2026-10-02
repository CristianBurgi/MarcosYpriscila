package com.tuapp.eventfoto.moderation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lleva la cuenta de los streams SSE abiertos con un link de moderador, para poder cerrarlos cuando el
 * organizador genera un link nuevo (si no, el link viejo seguiría recibiendo eventos hasta 30 minutos).
 * Los emitters siguen registrados en {@code SseBroadcaster} como cualquier otro: acá solo se los marca.
 */
@Slf4j
@Component
public class ModeratorStreamRegistry {

    private final Map<UUID, Set<SseEmitter>> streamsByEvent = new ConcurrentHashMap<>();

    public SseEmitter track(UUID eventId, SseEmitter emitter) {
        streamsByEvent.compute(eventId, (id, current) -> {
            Set<SseEmitter> set = current != null ? current : ConcurrentHashMap.newKeySet();
            set.add(emitter);
            return set;
        });
        Runnable untrack = () -> streamsByEvent.computeIfPresent(eventId, (id, set) -> {
            set.remove(emitter);
            return set.isEmpty() ? null : set;
        });
        emitter.onCompletion(untrack);
        emitter.onTimeout(untrack);
        emitter.onError(e -> untrack.run());
        return emitter;
    }

    /** Cierra todos los streams del moderador del evento. Devuelve cuántos cerró. */
    public int closeAll(UUID eventId) {
        Set<SseEmitter> set = streamsByEvent.remove(eventId);
        if (set == null) {
            return 0;
        }
        int closed = 0;
        for (SseEmitter emitter : set) {
            try {
                emitter.complete();
                closed++;
            } catch (Exception e) {
                log.debug("Stream de moderador ya cerrado: {}", e.toString());
            }
        }
        return closed;
    }

    public int openStreams(UUID eventId) {
        Set<SseEmitter> set = streamsByEvent.get(eventId);
        return set == null ? 0 : set.size();
    }
}
