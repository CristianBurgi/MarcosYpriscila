package com.tuapp.eventfoto.moderation;

import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.ModeratedEvent;
import com.tuapp.eventfoto.moderation.dto.ModeratorMessageDTO;
import com.tuapp.eventfoto.moderation.dto.ModeratorPage;
import com.tuapp.eventfoto.moderation.dto.ModeratorPhotoDTO;
import com.tuapp.eventfoto.realtime.SseBroadcaster;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * API del link de moderador. SOLO listar y borrar fotos y mensajes de UN evento, más su stream SSE: nada de
 * descargas, configuración, comentarios ni otros eventos. El evento sale de {token} y lo resuelve
 * EventAccessInterceptor (ámbito MODERATOR); acá solo se lee con @ModeratedEvent, jamás se busca por id sin
 * acotar al evento.
 */
@RestController
@RequestMapping("/api/v1/moderate/{token}")
@RequiredArgsConstructor
public class ModeratorController {

    private final ModeratorContentService contentService;
    private final ModeratorRateLimiter rateLimiter;
    private final ModeratorStreamRegistry streamRegistry;
    private final SseBroadcaster sseBroadcaster;

    @GetMapping("/photos")
    public ModeratorPage<ModeratorPhotoDTO> photos(@ModeratedEvent Event event,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "50") int size) {
        return contentService.listPhotos(event.getId(), page, size);
    }

    @GetMapping("/messages")
    public ModeratorPage<ModeratorMessageDTO> messages(@ModeratedEvent Event event,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "50") int size) {
        return contentService.listMessages(event.getId(), page, size);
    }

    @DeleteMapping("/photos/{photoId}")
    public ResponseEntity<Void> deletePhoto(@ModeratedEvent Event event, @PathVariable UUID photoId) {
        rateLimiter.checkDeleteAllowed(event.getModeratorToken());
        contentService.deletePhoto(event.getId(), photoId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/messages/{messageId}")
    public ResponseEntity<Void> deleteMessage(@ModeratedEvent Event event, @PathVariable UUID messageId) {
        rateLimiter.checkDeleteAllowed(event.getModeratorToken());
        contentService.deleteMessage(event.getId(), messageId);
        return ResponseEntity.noContent().build();
    }

    /** Mismo flujo de eventos que el stream público, pero por token (así el moderador nunca conoce el slug). */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@ModeratedEvent Event event) {
        return streamRegistry.track(event.getId(), sseBroadcaster.subscribe(event.getId()));
    }
}
