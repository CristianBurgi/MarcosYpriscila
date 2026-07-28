package com.tuapp.eventfoto.message.dto;

import com.tuapp.eventfoto.message.Message;

import java.time.Instant;
import java.util.UUID;

public record MessageResponseDTO(
    UUID id,
    UUID eventId,
    String authorName,
    String text,
    boolean isApproved,
    Instant createdAt
) {
    public static MessageResponseDTO fromEntity(Message message) {
        return new MessageResponseDTO(
            message.getId(),
            message.getEvent() != null ? message.getEvent().getId() : null,
            message.getAuthorName(),
            message.getText(),
            message.isApproved(),
            message.getCreatedAt()
        );
    }
}
