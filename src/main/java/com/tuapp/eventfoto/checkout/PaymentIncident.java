package com.tuapp.eventfoto.checkout;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Pago aprobado que no creó nada y requiere revisión manual (V16). Uno por payment_id. La pantalla para resolverlos
 * (y {@code resolvedAt}) es de la 9.7; nunca hay reembolso automático.
 */
@Entity
@Table(name = "payment_incident",
        uniqueConstraints = @UniqueConstraint(name = "uq_payment_incident_payment_id", columnNames = "payment_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentIncident {

    public enum Reason {
        /** external_reference que no es una compra nuestra (descartada, desconocida o con formato inválido). */
        UNKNOWN_REFERENCE,
        AMOUNT_MISMATCH,
        CURRENCY_MISMATCH,
        /** Aprobado pero con parte del monto ya devuelto. */
        PARTIALLY_REFUNDED,
        /** Compra nueva cuyo email ya tiene cuenta (otra compra o el superadmin la creó entre medio). */
        EMAIL_ALREADY_REGISTERED,
        /** Segundo pago aprobado para una compra ya procesada (o ya asociada a otro pago): cobro doble. */
        DUPLICATE_PAYMENT,
        /** Recompra de una cuenta que ya no existe. */
        ORGANIZER_MISSING,
        /** Un pago ya procesado volvió como reembolsado o con contracargo: sin acción automática. */
        REFUNDED,
        CHARGED_BACK
    }

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private String paymentId;

    @Column(name = "external_reference")
    private String externalReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Reason reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
