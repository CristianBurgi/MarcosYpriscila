package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.event.dto.EventResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    /**
     * GET /api/v1/events/{slug}
     * Devuelve los datos básicos del evento (Boda de Marcos y Priscila).
     */
    @GetMapping("/{slug}")
    public ResponseEntity<EventResponseDTO> getEventBySlug(@PathVariable String slug) {
        EventResponseDTO event = eventService.getEventBySlug(slug);
        return ResponseEntity.ok(event);
    }
}
