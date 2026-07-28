package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.common.exception.ResourceNotFoundException;
import com.tuapp.eventfoto.event.dto.EventResponseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventServiceImpl implements EventService {

    private final EventRepository eventRepository;

    @Override
    @Transactional(readOnly = true)
    public EventResponseDTO getEventBySlug(String slug) {
        Event event = getEventEntityBySlug(slug);
        return EventResponseDTO.fromEntity(event);
    }

    @Override
    @Transactional(readOnly = true)
    public Event getEventEntityBySlug(String slug) {
        return eventRepository.findBySlug(slug.toLowerCase().trim())
                .orElseThrow(() -> new ResourceNotFoundException("No se encontró el evento con el slug: '" + slug + "'"));
    }

    @Override
    @Transactional
    public EventResponseDTO toggleEventActiveStatus(String slug) {
        Event event = getEventEntityBySlug(slug);
        event.setActive(!event.isActive());
        Event updated = eventRepository.save(event);
        log.info("Estado del evento '{}' cambiado a: {}", slug, updated.isActive() ? "ACTIVO" : "CERRADO");
        return EventResponseDTO.fromEntity(updated);
    }
}
