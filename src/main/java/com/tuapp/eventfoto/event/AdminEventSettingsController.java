package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.event.dto.EventSettingsDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Wizard de onboarding y tarjeta "Personalización": los mismos endpoints para los dos. Están en
 * {@link WizardRouteExceptions}, así que responden aunque el wizard no esté completo; el acceso al evento
 * lo verifica EventAccessInterceptor como en cualquier ruta del panel.
 */
@RestController
@RequestMapping("/api/v1/admin/events/{slug}")
@RequiredArgsConstructor
public class AdminEventSettingsController {

    private final EventSettingsService settingsService;

    /** PUT .../date   body: {"date": "2026-12-10"} */
    @PutMapping("/date")
    public ResponseEntity<EventSettingsDTO> updateDate(@OwnedEvent Event event, @RequestBody EventSettingsDTO.DateRequest request) {
        settingsService.updateDate(event, request.date());
        return ResponseEntity.ok(settingsService.view(event.getId()));
    }

    /** PUT .../branding/color   body: {"color": "#2e4374"} o {"color": null} (paleta por defecto) */
    @PutMapping("/branding/color")
    public ResponseEntity<EventSettingsDTO> updateColor(@OwnedEvent Event event, @RequestBody EventSettingsDTO.ColorRequest request) {
        settingsService.updateColor(event, request.color());
        return ResponseEntity.ok(settingsService.view(event.getId()));
    }

    /** POST .../branding/image   multipart "file". Sin "consumes": el bloqueo y el 404 corren antes de leer el cuerpo. */
    @PostMapping("/branding/image")
    public ResponseEntity<EventSettingsDTO> uploadImage(@OwnedEvent Event event, @RequestParam("file") MultipartFile file) {
        settingsService.replaceImage(event, file);
        return ResponseEntity.ok(settingsService.view(event.getId()));
    }

    @DeleteMapping("/branding/image")
    public ResponseEntity<EventSettingsDTO> removeImage(@OwnedEvent Event event) {
        settingsService.removeImage(event);
        return ResponseEntity.ok(settingsService.view(event.getId()));
    }

    /** "Ir a mi panel". Idempotente. */
    @PostMapping("/wizard/complete")
    public ResponseEntity<EventSettingsDTO> completeWizard(@OwnedEvent Event event) {
        settingsService.completeWizard(event);
        return ResponseEntity.ok(settingsService.view(event.getId()));
    }
}
