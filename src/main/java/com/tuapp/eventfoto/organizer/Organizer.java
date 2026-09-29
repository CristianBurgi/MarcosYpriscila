package com.tuapp.eventfoto.organizer;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Cuenta del organizador (el cliente dueño de sus eventos). {@code passwordHash} es
 * nullable: una cuenta creada por el superadmin para un evento sin costo no tiene
 * contraseña hasta que la persona la elige con su link de activación -- ver
 * {@link OrganizerToken}. {@code tokenVersion} se compara contra el claim del JWT en
 * cada request (ver JwtAuthenticationFilter); incrementarlo invalida todas las
 * sesiones ya emitidas de este organizador.
 */
@Entity
@Table(name = "organizer", indexes = {
    @Index(name = "idx_organizer_email", columnList = "email", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Organizer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Builder.Default
    @Column(name = "token_version", nullable = false)
    private int tokenVersion = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public boolean isActivated() {
        return passwordHash != null && !passwordHash.isBlank();
    }
}
