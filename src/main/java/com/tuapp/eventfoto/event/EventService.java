package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.event.dto.EventResponseDTO;

public interface EventService {

    EventResponseDTO getEventBySlug(String slug);

    Event getEventEntityBySlug(String slug);

    EventResponseDTO toggleEventActiveStatus(String slug);
}
