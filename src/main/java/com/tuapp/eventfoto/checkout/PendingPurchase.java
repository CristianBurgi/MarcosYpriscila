package com.tuapp.eventfoto.checkout;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Compra pendiente de pago (ver V15). Exactamente uno de dos modos: cliente existente ({@code organizerId}) o cliente
 * nuevo ({@code email} + {@code passwordHash}); la base lo exige con un CHECK. El id es el external_reference de MP.
 */
@Entity
@Table(name = "pending_purchase")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PendingPurchase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organizer_id")
    private UUID organizerId;

    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "event_name", nullable = false)
    private String eventName;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "mp_preference_id")
    private String mpPreferenceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "mp_payment_id")
    private String mpPaymentId;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
