package com.tuapp.eventfoto.organizer;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Token de un solo uso del organizador. Se persiste el HASH ({@link #tokenHash}),
 * nunca el token en claro -- el valor real solo existe en memoria el instante en que
 * se genera (para mostrárselo al superadmin) y en el link que recibe el organizador.
 */
@Entity
@Table(name = "organizer_token", indexes = {
    @Index(name = "idx_organizer_token_hash", columnList = "token_hash", unique = true),
    @Index(name = "idx_organizer_token_organizer_purpose", columnList = "organizer_id, purpose")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrganizerToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organizer_id", nullable = false)
    private Organizer organizer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrganizerTokenPurpose purpose;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public boolean isUsable(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }
}
