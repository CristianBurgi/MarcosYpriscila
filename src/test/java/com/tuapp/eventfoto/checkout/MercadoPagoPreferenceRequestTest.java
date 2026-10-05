package com.tuapp.eventfoto.checkout;

import com.mercadopago.client.preference.PreferenceRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Qué lleva (y qué NO lleva) la preferencia que se le manda a Mercado Pago. */
class MercadoPagoPreferenceRequestTest {

    private final UUID reference = UUID.randomUUID();
    private final OffsetDateTime from = OffsetDateTime.parse("2026-10-04T10:00:00.000-03:00");
    private final OffsetDateTime to = from.plusHours(24);
    private final PreferenceRequest request =
            MercadoPagoPreferenceGateway.buildRequest(reference, new BigDecimal("50000.00"), from, to, "https://event-foto.up.railway.app/compra/retorno",
                    "https://event-foto.up.railway.app/api/v1/checkout/webhook", true);

    @Test
    @DisplayName("external_reference = id de la compra; un ítem de monto configurado, ARS, título fijo")
    void referenceAndItem() {
        assertThat(request.getExternalReference()).isEqualTo(reference.toString());
        assertThat(request.getItems()).hasSize(1);
        var item = request.getItems().get(0);
        assertThat(item.getUnitPrice()).isEqualByComparingTo("50000");
        assertThat(item.getQuantity()).isEqualTo(1);
        assertThat(item.getCurrencyId()).isEqualTo("ARS");
        assertThat(item.getTitle()).isEqualTo("Evento EventFoto");
    }

    @Test
    @DisplayName("back_urls apuntan a /compra/retorno; notification_url al webhook y auto_return=approved (fase 9.4)")
    void backUrlsAndNoNotificationUrl() {
        assertThat(request.getBackUrls().getSuccess()).isEqualTo("https://event-foto.up.railway.app/compra/retorno");
        assertThat(request.getBackUrls().getFailure()).isEqualTo("https://event-foto.up.railway.app/compra/retorno");
        assertThat(request.getBackUrls().getPending()).isEqualTo("https://event-foto.up.railway.app/compra/retorno");
        assertThat(request.getNotificationUrl()).isEqualTo("https://event-foto.up.railway.app/api/v1/checkout/webhook");
        assertThat(request.getAutoReturn()).isEqualTo("approved");
    }

    @Test
    @DisplayName("Sin https pública (desarrollo local): ni notification_url ni auto_return, que MP rechazaría")
    void localDevelopmentHasNoWebhookNorAutoReturn() {
        PreferenceRequest local = MercadoPagoPreferenceGateway.buildRequest(reference, new BigDecimal("50000.00"), from, to,
                "http://localhost:8080/compra/retorno", null, false);
        assertThat(local.getNotificationUrl()).isNull();
        assertThat(local.getAutoReturn()).isNull();
    }

    @Test
    @DisplayName("binary_mode, vencimiento a 24 hs y sin efectivo (ticket) ni cajero (atm)")
    void binaryModeExpirationAndPaymentTypes() {
        assertThat(request.getBinaryMode()).isTrue();
        assertThat(request.getExpires()).isTrue();
        assertThat(request.getExpirationDateFrom()).isEqualTo(from);
        assertThat(request.getExpirationDateTo()).isEqualTo(to);
        assertThat(request.getPaymentMethods().getExcludedPaymentTypes())
                .extracting(type -> type.getId()).containsExactlyInAnyOrder("ticket", "atm");
    }

    @Test
    @DisplayName("No lleva datos del pagador")
    void noPayerData() {
        assertThat(request.getPayer()).isNull();
    }
}
