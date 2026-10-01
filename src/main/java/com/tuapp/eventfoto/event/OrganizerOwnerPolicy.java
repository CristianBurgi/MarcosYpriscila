package com.tuapp.eventfoto.event;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * El organizador logueado puede operar sobre los eventos que le pertenecen. La pregunta
 * va a la base ({@code existsByIdAndOrganizerId}) y no se resuelve leyendo
 * {@code event.getOrganizer()}: es una relación lazy y esta política corre fuera de
 * cualquier transacción.
 */
@Component
@RequiredArgsConstructor
public class OrganizerOwnerPolicy implements EventAccessPolicy {

    private final EventRepository eventRepository;

    @Override
    public boolean canAccess(Authentication authentication, Event event) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UUID organizerId)) {
            return false;
        }
        return eventRepository.existsByIdAndOrganizerId(event.getId(), organizerId);
    }
}
