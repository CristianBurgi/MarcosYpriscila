package com.tuapp.eventfoto.superadmin;

import com.tuapp.eventfoto.common.config.AppUrls;
import com.tuapp.eventfoto.common.exception.ResourceNotFoundException;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventCreationService;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.organizer.OrganizerTokenPurpose;
import com.tuapp.eventfoto.organizer.OrganizerTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Única función de superadmin de este bloque: crear un evento sin costo. Usa
 * EventCreationService (el mismo servicio que va a usar la confirmación de pago en
 * la 9.4) con origin=COURTESY.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuperadminEventService {

    private final OrganizerRepository organizerRepository;
    private final OrganizerTokenService organizerTokenService;
    private final EventCreationService eventCreationService;
    private final AppUrls appUrls;

    public record FreeEventResult(Event event, Organizer organizer, boolean newOrganizer, String activationLink) {
    }

    @Transactional
    public FreeEventResult createFreeEvent(String email, String eventName, String reason) {
        String normalizedEmail = email.trim().toLowerCase();
        Organizer existing = organizerRepository.findByEmailIgnoreCase(normalizedEmail).orElse(null);
        boolean isNew = existing == null;

        Organizer organizer = isNew
                ? organizerRepository.save(Organizer.builder().email(normalizedEmail).build())
                : existing;

        Event event = eventCreationService.createEvent(organizer, eventName, EventOrigin.COURTESY, reason);

        // El link de activación solo se genera para una cuenta recién creada -- si el
        // email ya tenía cuenta (activada o no), generar uno nuevo es una acción aparte
        // y deliberada (ver regenerateActivationLink), no un efecto secundario de sumarle
        // un evento más.
        String activationLink = null;
        if (isNew) {
            var issued = organizerTokenService.issue(organizer, OrganizerTokenPurpose.ACCOUNT_ACTIVATION);
            activationLink = buildActivationLink(issued.rawToken());
        }

        log.info("Evento sin costo '{}' (slug '{}') creado para {} -- organizer {} ({}), motivo: {}",
                event.getName(), event.getSlug(), normalizedEmail, organizer.getId(),
                isNew ? "cuenta nueva" : "cuenta existente", reason);

        return new FreeEventResult(event, organizer, isNew, activationLink);
    }

    /** El link anterior sin usar queda invalidado (ver OrganizerTokenService.issue). */
    @Transactional
    public String regenerateActivationLink(UUID organizerId) {
        Organizer organizer = organizerRepository.findById(organizerId)
                .orElseThrow(() -> new ResourceNotFoundException("No se encontró el organizador con ID: " + organizerId));
        if (organizer.isActivated()) {
            throw new IllegalStateException("La cuenta de " + organizer.getEmail() + " ya está activada");
        }
        var issued = organizerTokenService.issue(organizer, OrganizerTokenPurpose.ACCOUNT_ACTIVATION);
        log.info("Link de activación regenerado para organizer {} ({})", organizer.getId(), organizer.getEmail());
        return buildActivationLink(issued.rawToken());
    }

    private String buildActivationLink(String rawToken) {
        return appUrls.baseUrl() + "/activar-cuenta?token=" + rawToken;
    }
}
