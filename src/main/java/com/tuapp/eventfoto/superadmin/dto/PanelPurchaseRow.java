package com.tuapp.eventfoto.superadmin.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Compra pendiente tal como la ve el superadmin. Sin password_hash, sin mp_preference_id y con solo los primeros 8
 * caracteres del external_reference (alcanza para cruzarlo con los logs; completo daría acceso al estado de la compra).
 */
public record PanelPurchaseRow(Instant createdAt, String email, String eventName, BigDecimal amount,
                               boolean hasPreference, String mpPaymentId, String lastPaymentStatus, String reference) {

    public PanelPurchaseRow(Instant createdAt, String email, String eventName, BigDecimal amount,
                            boolean hasPreference, String mpPaymentId, String lastPaymentStatus, UUID id) {
        this(createdAt, email, eventName, amount, hasPreference, mpPaymentId, lastPaymentStatus, id.toString().substring(0, 8));
    }
}
