package com.tuapp.eventfoto.checkout;

import com.mercadopago.client.preference.PreferenceBackUrlsRequest;
import com.mercadopago.client.preference.PreferenceClient;
import com.mercadopago.client.preference.PreferenceItemRequest;
import com.mercadopago.client.preference.PreferencePaymentMethodsRequest;
import com.mercadopago.client.preference.PreferencePaymentTypeRequest;
import com.mercadopago.client.preference.PreferenceRequest;
import com.mercadopago.MercadoPagoConfig;
import com.mercadopago.core.MPRequestOptions;
import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.tuapp.eventfoto.common.config.AppUrls;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Checkout Pro con el SDK oficial (sdk-java 3.7.0). Credenciales y timeouts por request (MPRequestOptions), no por la
 * configuración estática global del SDK. Nunca se loguea un cuerpo de MP ni el access token: de un fallo solo se
 * registra el tipo de error y el status HTTP.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.checkout.enabled", havingValue = "true")
public class MercadoPagoPreferenceGateway implements PaymentPreferenceGateway {

    /** Título fijo: nada de lo que escribe la persona viaja a MP. */
    static final String ITEM_TITLE = "Evento EventFoto";
    static final String ITEM_ID = "evento-eventfoto";
    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final int CONNECTION_TIMEOUT_MS = 5_000;
    private static final int SOCKET_TIMEOUT_MS = 10_000;

    private final CheckoutSettings settings;
    private final AppUrls appUrls;
    private final PreferenceClient preferenceClient = new PreferenceClient();

    public MercadoPagoPreferenceGateway(CheckoutSettings settings, AppUrls appUrls) {
        this.settings = settings;
        this.appUrls = appUrls;
        // El logger del SDK (java.util.logging) puede volcar requests y responses: apagado.
        MercadoPagoConfig.setLoggingLevel(Level.OFF);
    }

    @Override
    public Preference createPreference(UUID externalReference, BigDecimal amount, Instant expiresAt) {
        OffsetDateTime from = OffsetDateTime.now(ARGENTINA).truncatedTo(ChronoUnit.MILLIS);
        OffsetDateTime to = expiresAt.atZone(ARGENTINA).toOffsetDateTime().truncatedTo(ChronoUnit.MILLIS);
        PreferenceRequest request = buildRequest(externalReference, amount, from, to, appUrls.checkoutReturnUrl());
        MPRequestOptions options = MPRequestOptions.builder()
                .accessToken(settings.accessToken())
                .connectionTimeout(CONNECTION_TIMEOUT_MS)
                .connectionRequestTimeout(CONNECTION_TIMEOUT_MS)
                .socketTimeout(SOCKET_TIMEOUT_MS)
                .customHeaders(Map.of("X-Idempotency-Key", externalReference.toString()))
                .build();
        try {
            com.mercadopago.resources.preference.Preference created = preferenceClient.create(request, options);
            if (created == null || created.getId() == null || created.getInitPoint() == null) {
                throw new IllegalStateException("respuesta de MP sin id o init_point");
            }
            return new Preference(created.getId(), created.getInitPoint());
        } catch (MPApiException e) {
            log.warn("Mercado Pago rechazó la preferencia (external_reference={}): MPApiException, status HTTP {}",
                    externalReference, e.getStatusCode());
            throw new PaymentGatewayException(e);
        } catch (MPException | RuntimeException e) {
            log.warn("No se pudo crear la preferencia en Mercado Pago (external_reference={}): {}",
                    externalReference, e.getClass().getSimpleName());
            throw new PaymentGatewayException(e);
        }
    }

    /**
     * La preferencia completa. Lo que NO lleva, a propósito: notification_url (el webhook es de la 9.4: sin esa URL MP
     * no llama a nada), datos del pagador (ni el email) y el nombre del evento. Pública de paquete para testearla.
     */
    static PreferenceRequest buildRequest(UUID externalReference, BigDecimal amount,
                                          OffsetDateTime expirationFrom, OffsetDateTime expirationTo, String returnUrl) {
        return PreferenceRequest.builder()
                .externalReference(externalReference.toString())
                .items(List.of(PreferenceItemRequest.builder()
                        .id(ITEM_ID)
                        .title(ITEM_TITLE)
                        .quantity(1)
                        .currencyId("ARS")
                        .unitPrice(amount)
                        .build()))
                .backUrls(PreferenceBackUrlsRequest.builder()
                        .success(returnUrl)
                        .failure(returnUrl)
                        .pending(returnUrl)
                        .build())
                // Solo pagos que se resuelven al instante (sin "pendiente"/"en revisión"): la 9.4 recibe aprobado o rechazado.
                .binaryMode(true)
                .expires(true)
                .expirationDateFrom(expirationFrom)
                .expirationDateTo(expirationTo)
                // Efectivo (ticket) y cajero (atm) tardan días en acreditarse: pueden aprobarse después de vencida la preferencia.
                .paymentMethods(PreferencePaymentMethodsRequest.builder()
                        .excludedPaymentTypes(List.of(
                                PreferencePaymentTypeRequest.builder().id("ticket").build(),
                                PreferencePaymentTypeRequest.builder().id("atm").build()))
                        .build())
                .build();
    }
}
