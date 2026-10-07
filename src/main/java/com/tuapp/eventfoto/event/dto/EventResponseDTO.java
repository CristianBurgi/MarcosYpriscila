package com.tuapp.eventfoto.event.dto;

import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.UploadWindow;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * uploadStatus: estado de la ventana de escritura de invitados ahora mismo (OPEN, NOT_READY, NOT_OPEN, CLOSED,
 * CLOSED_BY_ORGANIZER). uploadMessage / guestbookMessage: el texto para el invitado en upload.html y en el libro de
 * visitas, o null si está abierta.
 */
public record EventResponseDTO(
    UUID id,
    String name,
    String slug,
    LocalDate eventDate,
    boolean isActive,
    Instant createdAt,
    UploadWindow.Status uploadStatus,
    String uploadMessage,
    String guestbookMessage
) {
    public static EventResponseDTO fromEntity(Event event, UploadWindow window) {
        return new EventResponseDTO(
            event.getId(),
            event.getName(),
            event.getSlug(),
            event.getEventDate(),
            event.isActive(),
            event.getCreatedAt(),
            window.status(event),
            window.closedMessage(event, UploadWindow.GuestWrite.PHOTO_START),
            window.closedMessage(event, UploadWindow.GuestWrite.MESSAGE)
        );
    }
}
