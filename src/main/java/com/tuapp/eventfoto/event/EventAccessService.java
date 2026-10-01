package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.event.dto.EventSummaryDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Consultas de eventos por organizador. El control de acceso a UN evento no vive acá:
 * lo hace EventAccessInterceptor con las EventAccessPolicy.
 */
@Service
@RequiredArgsConstructor
public class EventAccessService {

    private final EventRepository eventRepository;

    /** "Mis eventos": solo los del organizador (el filtro lo hace la consulta). */
    @Transactional(readOnly = true)
    public List<EventSummaryDTO> listOwnedEvents(UUID organizerId) {
        return eventRepository.findSummariesByOrganizerId(organizerId);
    }
}
