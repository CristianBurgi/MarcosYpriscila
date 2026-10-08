package com.tuapp.eventfoto.superadmin.dto;

import com.tuapp.eventfoto.checkout.PaymentIncident;

import java.time.Instant;
import java.util.UUID;

/** Incidente de pago sin resolver; la referencia, recortada a 8 caracteres como en las compras pendientes. */
public record PanelIncidentRow(UUID id, PaymentIncident.Reason reason, String paymentId, String reference, Instant createdAt) {

    public PanelIncidentRow {
        if (reference != null && reference.length() > 8) {
            reference = reference.substring(0, 8);
        }
    }
}
