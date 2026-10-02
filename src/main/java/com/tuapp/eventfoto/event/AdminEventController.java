package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.event.dto.EventResponseDTO;
import com.tuapp.eventfoto.photo.GuestQuotaService;
import com.tuapp.eventfoto.photo.dto.GuestPhotoLimitDTO;
import com.tuapp.eventfoto.photo.dto.UpdateGuestPhotoLimitRequestDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/events/{slug}")
@RequiredArgsConstructor
public class AdminEventController {

    private final EventService eventService;
    private final GuestQuotaService guestQuotaService;

    /**
     * PATCH /api/v1/admin/events/{slug}/toggle-status
     * Abre o cierra el evento para recibir o rechazar nuevas subidas de fotografías.
     */
    @PatchMapping("/toggle-status")
    public ResponseEntity<EventResponseDTO> toggleEventStatus(@OwnedEvent Event event) {
        EventResponseDTO updatedEvent = eventService.toggleEventActiveStatus(event.getSlug());
        return ResponseEntity.ok(updatedEvent);
    }

    /**
     * PUT /api/v1/admin/events/{slug}/guest-photo-limit   body: {"unlimited": true|false}
     * Elige entre "hasta N fotos por invitado" (N = default configurado) y "sin límite". Se puede cambiar con
     * el evento en curso. Devuelve el estado nuevo completo.
     */
    @PutMapping("/guest-photo-limit")
    public ResponseEntity<GuestPhotoLimitDTO> updateGuestPhotoLimit(
            @OwnedEvent Event event, @Valid @RequestBody UpdateGuestPhotoLimitRequestDTO request) {
        return ResponseEntity.ok(guestQuotaService.updateGuestPhotoLimit(event.getId(), request.unlimited()));
    }
}
