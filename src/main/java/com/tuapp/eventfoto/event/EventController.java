package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.event.dto.EventResponseDTO;
import com.tuapp.eventfoto.photo.GuestQuotaService;
import com.tuapp.eventfoto.photo.dto.GuestQuotaResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;
    private final GuestQuotaService guestQuotaService;

    /**
     * GET /api/v1/events/{slug}
     * Devuelve los datos básicos del evento (Boda de Marcos y Priscila).
     */
    @GetMapping("/{slug}")
    public ResponseEntity<EventResponseDTO> getEventBySlug(@PathVariable String slug) {
        EventResponseDTO event = eventService.getEventBySlug(slug);
        return ResponseEntity.ok(event);
    }

    /**
     * GET /api/v1/events/{slug}/guest-quota?token={guestToken}
     * Devuelve cuántas fotos le quedan a un invitado anónimo (identificado por su
     * guestToken de localStorage) en este evento. Endpoint público, sin auth.
     */
    @GetMapping("/{slug}/guest-quota")
    public ResponseEntity<GuestQuotaResponseDTO> getGuestQuota(
            @PathVariable String slug,
            @RequestParam String token) {
        Event event = eventService.getEventEntityBySlug(slug);
        int remaining = guestQuotaService.getRemainingPhotos(event.getId(), token);
        return ResponseEntity.ok(new GuestQuotaResponseDTO(remaining, guestQuotaService.getMaxPhotosPerGuest()));
    }
}
