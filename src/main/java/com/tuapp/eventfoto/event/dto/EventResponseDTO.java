package com.tuapp.eventfoto.event.dto;

import com.tuapp.eventfoto.event.Event;

import java.time.Instant;
import java.util.UUID;

public record EventResponseDTO(
    UUID id,
    String name,
    String slug,
    Instant eventDate,
    Instant uploadDeadline,
    boolean isActive,
    Instant createdAt
) {
    public static EventResponseDTO fromEntity(Event event) {
        return new EventResponseDTO(
            event.getId(),
            event.getName(),
            event.getSlug(),
            event.getEventDate(),
            event.getUploadDeadline(),
            event.isActive(),
            event.getCreatedAt()
        );
    }
}
