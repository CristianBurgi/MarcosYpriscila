package com.tuapp.eventfoto.moderation.dto;

import java.time.Instant;
import java.util.UUID;

public record ModeratorMessageDTO(UUID id, String authorName, String text, Instant createdAt) {
}
