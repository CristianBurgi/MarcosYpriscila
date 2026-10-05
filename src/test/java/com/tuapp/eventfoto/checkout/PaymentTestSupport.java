package com.tuapp.eventfoto.checkout;

import com.tuapp.eventfoto.checkout.PaymentLookupGateway.PaymentInfo;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Helpers de los tests de la 9.4: pagos de la API simulados y notificaciones firmadas como las manda Mercado Pago. */
final class PaymentTestSupport {

    /** El de application-test.yml. */
    static final String WEBHOOK_SECRET = "secreto-webhook-falso-solo-para-tests";
    static final BigDecimal PRICE = new BigDecimal("50000.00");

    private PaymentTestSupport() {
    }

    static PaymentInfo payment(long id, String status, UUID reference) {
        return new PaymentInfo(id, status, "ARS", PRICE, BigDecimal.ZERO, reference == null ? null : reference.toString());
    }

    static PaymentInfo payment(long id, String status, String currency, BigDecimal amount, String reference) {
        return new PaymentInfo(id, status, currency, amount, BigDecimal.ZERO, reference);
    }

    /** Manifiesto de MP: id:[data.id];request-id:[x-request-id];ts:[ts]; firmado con HMAC-SHA256 en hex. */
    static String sign(String secret, String dataId, String requestId, String ts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String manifest = "id:" + dataId.toLowerCase() + ";request-id:" + requestId + ";ts:" + ts + ";";
            return HexFormat.of().formatHex(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Notificación de pago bien firmada. El cuerpo lleva datos que el servidor NO debe usar (un status inventado). */
    static MockHttpServletRequestBuilder signedWebhook(long paymentId) {
        String ts = String.valueOf(System.currentTimeMillis());
        String requestId = UUID.randomUUID().toString();
        String id = String.valueOf(paymentId);
        return post("/api/v1/checkout/webhook")
                .queryParam("data.id", id)
                .queryParam("type", "payment")
                .header("x-request-id", requestId)
                .header("x-signature", "ts=" + ts + ",v1=" + sign(WEBHOOK_SECRET, id, requestId, ts))
                .contentType("application/json")
                .content("{\"action\":\"payment.updated\",\"type\":\"payment\",\"data\":{\"id\":\"" + id + "\"},"
                        + "\"status\":\"approved\",\"cuerpo\":\"cuerpo-secreto-de-mp\"}");
    }
}
