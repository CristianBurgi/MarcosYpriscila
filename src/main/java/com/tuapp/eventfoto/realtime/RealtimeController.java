package com.tuapp.eventfoto.realtime;

import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/events/{slug}/stream")
@RequiredArgsConstructor
public class RealtimeController {

    private final EventService eventService;
    private final SseBroadcaster sseBroadcaster;

    /**
     * GET /api/v1/events/{slug}/stream
     * Abre un canal Server-Sent Events (SSE) en vivo para el evento indicado por slug.
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeToEventStream(@PathVariable String slug) {
        Event event = eventService.getEventEntityBySlug(slug);
        return sseBroadcaster.subscribe(event.getId());
    }
}
