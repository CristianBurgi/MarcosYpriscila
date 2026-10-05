package com.tuapp.eventfoto.checkout;

import java.math.BigDecimal;
import java.util.Optional;

/** Consulta de un pago real a la API de Mercado Pago. Los tests la reemplazan (el CI nunca llama a MP). */
public interface PaymentLookupGateway {

    /** Lo único que se usa del pago para decidir. Todo sale de la API, nada de la query ni de la notificación. */
    record PaymentInfo(long id, String status, String currencyId, BigDecimal amount, BigDecimal amountRefunded,
                       String externalReference) {
    }

    /**
     * @return vacío si MP no conoce ese pago (404)
     * @throws PaymentGatewayException si MP no responde o falla (timeouts, 5xx, credenciales)
     */
    Optional<PaymentInfo> findPayment(long paymentId);
}
