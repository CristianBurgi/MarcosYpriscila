package com.tuapp.eventfoto.superadmin;

import com.tuapp.eventfoto.event.EventLifecycleService;
import com.tuapp.eventfoto.superadmin.dto.CreateFreeEventRequestDTO;
import com.tuapp.eventfoto.superadmin.dto.CreateFreeEventResponseDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/superadmin")
@RequiredArgsConstructor
public class SuperadminEventController {

    private final SuperadminEventService superadminEventService;
    private final EventLifecycleService eventLifecycleService;

    @PostMapping("/events/free-event")
    public ResponseEntity<CreateFreeEventResponseDTO> createFreeEvent(@Valid @RequestBody CreateFreeEventRequestDTO request) {
        SuperadminEventService.FreeEventResult result = superadminEventService.createFreeEvent(
                request.email(), request.eventName(), request.reason());

        return ResponseEntity.ok(new CreateFreeEventResponseDTO(
                result.event().getId(),
                result.event().getName(),
                result.event().getSlug(),
                result.organizer().getId(),
                result.organizer().getEmail(),
                result.newOrganizer(),
                result.activationLink()
        ));
    }

    @PostMapping("/organizers/{organizerId}/resend-activation")
    public ResponseEntity<?> resendActivation(@PathVariable UUID organizerId) {
        try {
            String activationLink = superadminEventService.regenerateActivationLink(organizerId);
            return ResponseEntity.ok(Map.of("activationLink", activationLink));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Corre ya el mismo proceso que el job diario de ciclo de vida (recordatorios y borrado de álbumes vencidos).
     * Idempotente. En el log queda quién lo disparó y qué hizo. 409 si ya hay una corrida en curso.
     */
    @PostMapping("/lifecycle/run")
    public ResponseEntity<?> runLifecycle(Authentication authentication) {
        return eventLifecycleService.run("superadmin " + authentication.getName())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Ya hay una corrida en curso.")));
    }
}
