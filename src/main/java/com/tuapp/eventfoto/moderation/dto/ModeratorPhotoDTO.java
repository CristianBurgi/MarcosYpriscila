package com.tuapp.eventfoto.moderation.dto;

import java.time.Instant;
import java.util.UUID;

/** Lo único que el moderador necesita ver de una foto: sin storageKey, sin eventId, sin comentarios. */
public record ModeratorPhotoDTO(UUID id, String url, String uploaderName, Instant createdAt) {
}
