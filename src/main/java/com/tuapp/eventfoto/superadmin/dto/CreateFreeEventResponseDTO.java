package com.tuapp.eventfoto.superadmin.dto;

import java.util.UUID;

public record CreateFreeEventResponseDTO(
        UUID eventId,
        String eventName,
        String slug,
        UUID organizerId,
        String organizerEmail,
        boolean newOrganizer,
        /** null si el organizador ya existía y estaba activado -- no hace falta reactivarlo. */
        String activationLink
) {}
