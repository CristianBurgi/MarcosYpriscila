package com.tuapp.eventfoto.photo;

import com.tuapp.eventfoto.event.Event;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Lleva la cuenta de cuántas fotos subió cada invitado anónimo (identificado por
 * un token generado en el frontend y persistido en localStorage) en un evento.
 *
 * El contador `photosUploaded` es monotónico: solo sube, nunca baja. Rechazar o
 * borrar una foto no le devuelve el cupo al invitado (ver GuestQuotaService).
 */
@Entity
@Table(name = "guest_quotas", indexes = {
    @Index(name = "idx_guest_quotas_event_id", columnList = "event_id")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uq_guest_quotas_event_token", columnNames = {"event_id", "guest_token"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GuestQuota {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(name = "guest_token", nullable = false, length = 64)
    private String guestToken;

    @Builder.Default
    @Column(name = "photos_uploaded", nullable = false)
    private int photosUploaded = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
