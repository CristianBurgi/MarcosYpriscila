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

    /** Fase 9.4 (V16): evento creado al confirmar el pago. */
    @Column(name = "event_id")
    private UUID eventId;

    /** Fase 9.4 (V16): true si la confirmación creó la cuenta, false si fue una recompra. */
    @Column(name = "created_organizer")
    private Boolean createdOrganizer;

    /** Fase 9.4 (V16): reserva del mail de confirmación (uno solo por compra). */
    @Column(name = "confirmation_email_sent_at")
    private Instant confirmationEmailSentAt;

    /** Fase 9.4 (V16): último estado de un pago no aprobado (rejected, cancelled...). */
    @Column(name = "last_payment_status")
    private String lastPaymentStatus;

    /** Recompra (o compra ya procesada de una cuenta existente): sin email propio en la compra. */
    public boolean isRepurchase() {
        return processedAt != null ? Boolean.FALSE.equals(createdOrganizer) : organizerId != null;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
