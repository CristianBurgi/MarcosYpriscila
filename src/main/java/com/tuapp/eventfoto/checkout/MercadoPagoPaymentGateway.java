package com.tuapp.eventfoto.checkout;

import com.mercadopago.client.payment.PaymentClient;
import com.mercadopago.core.MPRequestOptions;
import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.resources.payment.Payment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * GET /v1/payments/{id} con el SDK oficial (3.7.0), con nuestro access token y timeouts explícitos. Nunca se loguea
 * la respuesta de MP (trae datos del pagador): de un fallo solo el tipo de error y el status HTTP.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.checkout.enabled", havingValue = "true")
public class MercadoPagoPaymentGateway implements PaymentLookupGateway {

    private static final int CONNECTION_TIMEOUT_MS = 5_000;
    private static final int SOCKET_TIMEOUT_MS = 10_000;

    private final CheckoutSettings settings;
    private final PaymentClient paymentClient = new PaymentClient();

    public MercadoPagoPaymentGateway(CheckoutSettings settings) {
        this.settings = settings;
    }

    @Override
    public Optional<PaymentInfo> findPayment(long paymentId) {
        MPRequestOptions options = MPRequestOptions.builder()
                .accessToken(settings.accessToken())
                .connectionTimeout(CONNECTION_TIMEOUT_MS)
                .connectionRequestTimeout(CONNECTION_TIMEOUT_MS)
                .socketTimeout(SOCKET_TIMEOUT_MS)
                .build();
        try {
            Payment payment = paymentClient.get(paymentId, options);
            if (payment == null || payment.getId() == null) {
                throw new IllegalStateException("respuesta de MP sin id de pago");
            }
            return Optional.of(new PaymentInfo(payment.getId(), payment.getStatus(), payment.getCurrencyId(),
                    payment.getTransactionAmount(), payment.getTransactionAmountRefunded(), payment.getExternalReference()));
        } catch (MPApiException e) {
            if (e.getStatusCode() == 404) {
                return Optional.empty();
            }
            log.warn("Mercado Pago no devolvió el pago payment_id={}: MPApiException, status HTTP {}", paymentId, e.getStatusCode());
            throw new PaymentGatewayException(e);
        } catch (MPException | RuntimeException e) {
            log.warn("No se pudo consultar el pago payment_id={} en Mercado Pago: {}", paymentId, e.getClass().getSimpleName());
            throw new PaymentGatewayException(e);
        }
    }
}
