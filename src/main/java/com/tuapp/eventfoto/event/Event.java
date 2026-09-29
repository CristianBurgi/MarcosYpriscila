package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.organizer.Organizer;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "events", indexes = {
    @Index(name = "idx_events_slug", columnList = "slug", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organizer_id", nullable = false)
    private Organizer organizer;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true, length = 100)
    private String slug;

    /**
     * Fecha del evento (sin hora), nullable: un evento sin costo se crea solo con
     * nombre + email, la fecha se completa después con el wizard (ver
     * {@link #wizardCompletedAt}).
     */
    @Column(name = "event_date")
    private LocalDate eventDate;

    @Column(name = "upload_deadline", nullable = false)
    private Instant uploadDeadline;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventOrigin origin;

    /** Obligatorio (validado en el servicio de creación) cuando origin=COURTESY. */
    @Column(name = "origin_reason", length = 500)
    private String originReason;

    @Column(name = "wizard_completed_at")
    private Instant wizardCompletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
