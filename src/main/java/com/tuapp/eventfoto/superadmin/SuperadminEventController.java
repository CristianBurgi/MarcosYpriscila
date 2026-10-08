package com.tuapp.eventfoto.superadmin;

import com.tuapp.eventfoto.event.EventLifecycleService;
import com.tuapp.eventfoto.superadmin.dto.CreateFreeEventRequestDTO;
import com.tuapp.eventfoto.superadmin.dto.CreateFreeEventResponseDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * API del superadmin. Todos los POST exigen Content-Type: application/json (415 si no): un formulario de otro sitio
 * no puede mandar JSON sin un preflight de CORS, así que la protección no depende solo de la cookie SameSite=Strict.
 * Cada acción deja una línea "[SUPERADMIN] {email} {acción} {objeto} → {resultado}", también si se rechaza.
 */
@Slf4j
@RestController
@RequestMapping(path = "/api/v1/superadmin", consumes = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class SuperadminEventController {

    private final SuperadminEventService superadminEventService;
    private final SuperadminPanelService panelService;
    private final EventLifecycleService eventLifecycleService;

    public record RetentionRequest(LocalDate until) {
    }

    public record ConfirmPaymentRequest(String paymentId) {
    }

    @PostMapping("/events/free-event")
    public ResponseEntity<CreateFreeEventResponseDTO> createFreeEvent(@Valid @RequestBody CreateFreeEventRequestDTO request,
                                                                      Authentication auth) {
        SuperadminEventService.FreeEventResult result = audited(auth, "crear-evento-sin-costo", request.email(),
                () -> superadminEventService.createFreeEvent(request.email(), request.eventName(), request.reason()),
                r -> "creado " + r.event().getSlug() + (r.newOrganizer() ? " (cuenta nueva)" : " (cuenta existente)"));

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

    /** El link anterior sin usar deja de funcionar. El link nuevo va en la respuesta, nunca al log. */
    @PostMapping("/organizers/{organizerId}/resend-activation")
    public Map<String, String> resendActivation(@PathVariable UUID organizerId, Authentication auth) {
        String activationLink = audited(auth, "link-activacion", "organizer " + organizerId, () -> {
            try {
                return superadminEventService.regenerateActivationLink(organizerId);
            } catch (IllegalStateException e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
            }
        }, link -> "generado (el anterior queda invalidado)");
        return Map.of("activationLink", activationLink);
    }

    @PostMapping("/events/{eventId}/retention")
    public SuperadminPanelService.RetentionResult overrideRetention(@PathVariable UUID eventId,
                                                                    @RequestBody RetentionRequest request,
                                                                    Authentication auth) {
        return audited(auth, "extender-vigencia", "evento " + eventId + " hasta " + request.until(),
                () -> panelService.overrideRetention(eventId, request.until()),
                r -> "borrado el " + r.deletionDate() + (r.reminderReset() ? ", recordatorio reiniciado" : ""));
    }

    @PostMapping("/payments/confirm")
    public Map<String, String> confirmPayment(@RequestBody ConfirmPaymentRequest request, Authentication auth) {
        var outcome = audited(auth, "marcar-pago-aprobado", "payment_id " + request.paymentId(),
                () -> panelService.confirmPayment(request.paymentId()), Enum::name);
        return Map.of("outcome", outcome.name());
    }

    @PostMapping("/events/{eventId}/resend-confirmation")
    public Map<String, String> resendConfirmation(@PathVariable UUID eventId, Authentication auth) {
        audited(auth, "reenviar-mail-confirmacion", "evento " + eventId,
                () -> panelService.resendConfirmation(eventId), to -> "enviado");
        return Map.of("result", "sent");
    }

    @PostMapping("/incidents/{incidentId}/resolve")
    public Map<String, Boolean> resolveIncident(@PathVariable UUID incidentId, Authentication auth) {
        boolean resolved = audited(auth, "resolver-incidente", "incidente " + incidentId,
                () -> panelService.resolveIncident(incidentId), r -> r ? "resuelto" : "ya estaba resuelto");
        return Map.of("resolvedNow", resolved);
    }

    /**
     * Corre ya el mismo proceso que el job diario de ciclo de vida (recordatorios y borrado de álbumes vencidos).
     * Idempotente. En el log queda quién lo disparó y qué hizo. 409 si ya hay una corrida en curso.
     */
    @PostMapping("/lifecycle/run")
    public ResponseEntity<?> runLifecycle(Authentication auth) {
        var result = eventLifecycleService.run("superadmin " + auth.getName());
        log.info("[SUPERADMIN] {} correr-ciclo-de-vida - → {}", auth.getName(),
                result.map(Object::toString).orElse("rechazado: ya hay una corrida en curso"));
        return result
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Ya hay una corrida en curso.")));
    }

    private static <T> T audited(Authentication auth, String action, String object, Supplier<T> body, Function<T, String> describe) {
        try {
            T result = body.get();
            log.info("[SUPERADMIN] {} {} {} → {}", auth.getName(), action, object, describe.apply(result));
            return result;
        } catch (ResponseStatusException e) {
            log.info("[SUPERADMIN] {} {} {} → rechazado {}: {}", auth.getName(), action, object, e.getStatusCode().value(), e.getReason());
            throw e;
        } catch (RuntimeException e) {
            log.warn("[SUPERADMIN] {} {} {} → error {}: {}", auth.getName(), action, object, e.getClass().getSimpleName(), e.getMessage());
            throw e;
        }
    }
}
