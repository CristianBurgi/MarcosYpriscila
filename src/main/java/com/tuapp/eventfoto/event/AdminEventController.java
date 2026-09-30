package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.event.dto.EventResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/events/{slug}")
@RequiredArgsConstructor
public class AdminEventController {

    private final EventService eventService;

    /**
     * PATCH /api/v1/admin/events/{slug}/toggle-status
     * Abre o cierra el evento para recibir o rechazar nuevas subidas de fotografías.
     */
    @PatchMapping("/toggle-status")
    public ResponseEntity<EventResponseDTO> toggleEventStatus(@OwnedEvent Event event) {
        EventResponseDTO updatedEvent = eventService.toggleEventActiveStatus(event.getSlug());
        return ResponseEntity.ok(updatedEvent);
    }
}
