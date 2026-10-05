package com.tuapp.eventfoto.checkout;

import com.tuapp.eventfoto.common.config.RateLimiterService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static com.tuapp.eventfoto.checkout.PaymentTestSupport.WEBHOOK_SECRET;
import static com.tuapp.eventfoto.checkout.PaymentTestSupport.sign;
import static com.tuapp.eventfoto.checkout.PaymentTestSupport.signedWebhook;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fase 9.4: la firma del webhook y la superficie pública de la página de retorno. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentWebhookSecurityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private RateLimiterService rateLimiterService;
    @Autowired private PaymentIncidentRepository incidents;
    @MockBean private PaymentLookupGateway gateway;

    @AfterEach
    void cleanUp() {
        rateLimiterService.resetRateLimits();
        incidents.deleteAll();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder webhook(String dataId, String requestId, String signatureHeader) {
        var builder = post("/api/v1/checkout/webhook").queryParam("data.id", dataId).queryParam("type", "payment")
                .contentType(MediaType.APPLICATION_JSON).content("{\"data\":{\"id\":\"" + dataId + "\"}}");
        if (requestId != null) {
            builder.header("x-request-id", requestId);
        }
        if (signatureHeader != null) {
            builder.header("x-signature", signatureHeader);
        }
        return builder;
    }

    @Test
    @DisplayName("Firma ausente, inválida, con otro secreto, con ts manipulado o de otro data.id -> 401, sin consultar a MP")
    void invalidSignaturesAreRejectedBeforeCallingMercadoPago() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis());
        String rid = UUID.randomUUID().toString();
        String good = sign(WEBHOOK_SECRET, "5001", rid, ts);

        mockMvc.perform(webhook("5001", rid, null)).andExpect(status().isUnauthorized());
        mockMvc.perform(webhook("5001", rid, "ts=" + ts + ",v1=" + "0".repeat(64))).andExpect(status().isUnauthorized());
        mockMvc.perform(webhook("5001", rid, "ts=" + ts + ",v1=" + sign("otro-secreto", "5001", rid, ts))).andExpect(status().isUnauthorized());
        mockMvc.perform(webhook("5001", rid, "ts=" + (Long.parseLong(ts) + 1) + ",v1=" + good)).andExpect(status().isUnauthorized());
        mockMvc.perform(webhook("5002", rid, "ts=" + ts + ",v1=" + good)).andExpect(status().isUnauthorized());
        mockMvc.perform(webhook("5001", UUID.randomUUID().toString(), "ts=" + ts + ",v1=" + good)).andExpect(status().isUnauthorized());
        mockMvc.perform(webhook("5001", rid, "basura")).andExpect(status().isUnauthorized());

        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("Firma válida: se consulta el pago a MP con el id de la query")
    void validSignatureQueriesThePayment() throws Exception {
        when(gateway.findPayment(5003L)).thenReturn(Optional.of(PaymentTestSupport.payment(5003L, "rejected", UUID.randomUUID())));
        mockMvc.perform(signedWebhook(5003L)).andExpect(status().isOk());
        verify(gateway, times(1)).findPayment(5003L);
    }

    @Test
    @DisplayName("Tipo distinto de payment (merchant_order, etc.) con firma válida -> 200 sin consultar nada")
    void otherNotificationTypesAreIgnored() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis());
        String rid = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/checkout/webhook").queryParam("data.id", "5004").queryParam("type", "merchant_order")
                        .header("x-request-id", rid).header("x-signature", "ts=" + ts + ",v1=" + sign(WEBHOOK_SECRET, "5004", rid, ts)))
                .andExpect(status().isOk());
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("confirm y status: públicos (sin sesión), sin token CSRF, con no-store y no-referrer")
    void returnEndpointsArePublicWithoutCsrfAndNotCached() throws Exception {
        when(gateway.findPayment(anyLong())).thenReturn(Optional.empty());
        mockMvc.perform(post("/api/v1/checkout/confirm").contentType(MediaType.APPLICATION_JSON).content("{\"paymentId\":\"5005\"}"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        mockMvc.perform(post("/api/v1/checkout/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalReference\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    @DisplayName("confirm no devuelve el estado del pago (anti-enumeración) y rechaza ids que no son de MP")
    void confirmRevealsNothing() throws Exception {
        when(gateway.findPayment(5006L)).thenReturn(Optional.of(PaymentTestSupport.payment(5006L, "approved", UUID.randomUUID())));
        mockMvc.perform(post("/api/v1/checkout/confirm").contentType(MediaType.APPLICATION_JSON).content("{\"paymentId\":\"5006\"}"))
                .andExpect(status().isNoContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(""));
        mockMvc.perform(post("/api/v1/checkout/confirm").contentType(MediaType.APPLICATION_JSON).content("{\"paymentId\":\"1 OR 1=1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Rate limit del status: la consulta 41 en un minuto -> 429, con bucket propio (confirm y checkout siguen)")
    void statusRateLimitIsItsOwnBucket() throws Exception {
        String body = "{\"externalReference\":\"" + UUID.randomUUID() + "\"}";
        for (int i = 0; i < 40; i++) {
            mockMvc.perform(post("/api/v1/checkout/status").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/v1/checkout/status").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests());

        when(gateway.findPayment(anyLong())).thenReturn(Optional.empty());
        mockMvc.perform(post("/api/v1/checkout/confirm").contentType(MediaType.APPLICATION_JSON).content("{\"paymentId\":\"5007\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/checkout").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"mal\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Rate limit del confirm: la 41 en un minuto -> 429 (dimensionado para ~30 rondas en 90 s)")
    void confirmRateLimit() throws Exception {
        when(gateway.findPayment(anyLong())).thenReturn(Optional.empty());
        for (int i = 0; i < 40; i++) {
            mockMvc.perform(post("/api/v1/checkout/confirm").contentType(MediaType.APPLICATION_JSON).content("{\"paymentId\":\"5008\"}"))
                    .andExpect(status().isNoContent());
        }
        mockMvc.perform(post("/api/v1/checkout/confirm").contentType(MediaType.APPLICATION_JSON).content("{\"paymentId\":\"5008\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("Una referencia inexistente responde igual que una real sin confirmar (PENDING)")
    void unknownReferenceLooksPending() throws Exception {
        mockMvc.perform(post("/api/v1/checkout/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalReference\":\"no-es-un-uuid\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.state").value("PENDING"));
    }
}
