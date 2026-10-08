package com.tuapp.eventfoto.superadmin.dto;

import com.tuapp.eventfoto.event.EventOrigin;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Fila del listado del superadmin, armada en la consulta: "sin activar" sale de la base, el hash nunca se trae. */
public record PanelEventRow(UUID id, String name, String slug, UUID organizerId, String organizerEmail,
                            boolean organizerPending, LocalDate eventDate, EventOrigin origin, String originReason,
                            boolean active, Instant wizardCompletedAt, LocalDate retentionOverrideUntil,
                            Instant expiryReminderSentAt, Instant purgedAt, Instant createdAt) {
}
