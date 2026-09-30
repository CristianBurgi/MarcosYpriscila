package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Único lugar donde se decide si un organizador puede operar sobre un evento. Todo el
 * panel llega acá a través de {@link OwnedEvent} (ver OwnedEventArgumentResolver); en
 * el Bloque 3 esta misma llamada pasa a un HandlerInterceptor.
 *
 * "No existe" y "es de otro organizador" fallan con la MISMA excepción y el mismo
 * mensaje: si se distinguieran, el 404/403 serviría para descubrir qué slugs existen.
 */
@Service
@RequiredArgsConstructor
public class EventAccessService {

    public static final String EVENT_NOT_FOUND_MESSAGE = "Evento no encontrado";

    private final EventRepository eventRepository;

    @Transactional(readOnly = true)
    public Event requireOwnedEvent(String slug, UUID organizerId) {
        if (slug == null || organizerId == null) {
            throw new ResourceNotFoundException(EVENT_NOT_FOUND_MESSAGE);
        }
        return eventRepository.findBySlugAndOrganizerId(slug.toLowerCase().trim(), organizerId)
                .orElseThrow(() -> new ResourceNotFoundException(EVENT_NOT_FOUND_MESSAGE));
    }

    @Transactional(readOnly = true)
    public List<com.tuapp.eventfoto.event.dto.EventSummaryDTO> listOwnedEvents(UUID organizerId) {
        return eventRepository.findSummariesByOrganizerId(organizerId);
    }
}
