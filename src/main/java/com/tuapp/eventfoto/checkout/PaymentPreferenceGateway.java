package com.tuapp.eventfoto.checkout;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Frontera con Mercado Pago: los tests la reemplazan (el CI nunca llama a MP). */
public interface PaymentPreferenceGateway {

    record Preference(String id, String initPoint) {
    }

    /** @throws PaymentGatewayException si MP no responde o rechaza la preferencia */
    Preference createPreference(UUID externalReference, BigDecimal amount, Instant expiresAt);
}
