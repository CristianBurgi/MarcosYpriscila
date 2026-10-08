package com.tuapp.eventfoto.checkout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuapp.eventfoto.common.config.RateLimiterService;
import com.tuapp.eventfoto.common.email.EmailService;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.organizer.OrganizerTokenRepository;
import com.tuapp.eventfoto.superadmin.SuperadminEventService;
import com.tuapp.eventfoto.testsupport.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.tuapp.eventfoto.checkout.PaymentTestSupport.payment;
import static com.tuapp.eventfoto.checkout.PaymentTestSupport.signedWebhook;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 9.4: confirmación del pago por los dos caminos (webhook y regreso del navegador). La API de MP se mockea.
 * Sin @Transactional: el servicio abre y cierra sus propias transacciones (y manda el mail después del commit),
 * justamente lo que una transacción de test taparía. La concurrencia real se prueba contra Postgres aparte.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PurchaseConfirmationFlowTest {

    private static final String PASSWORD = "una-clave-larga-y-rara-93";
    private static final String EMAIL = "compradora@test.com";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PendingPurchaseRepository purchases;
    @Autowired private PaymentIncidentRepository incidents;
    @Autowired private OrganizerRepository organizers;
    @Autowired private OrganizerTokenRepository organizerTokens;
    @Autowired private EventRepository events;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RateLimiterService rateLimiterService;
    @Autowired private SuperadminEventService superadminEventService;
    @MockBean private PaymentLookupGateway gateway;
    @MockBean private EmailService emailService;

    @BeforeEach
    void setUp() {
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        rateLimiterService.resetRateLimits();
        incidents.deleteAll();
        purchases.deleteAll();
        organizerTokens.deleteAll();
        events.deleteAll();
        organizers.deleteAll();
    }

    private PendingPurchase newPurchase(String email) {
        return purchases.save(PendingPurchase.builder()
                .email(email).passwordHash(passwordEncoder.encode(PASSWORD))
                .eventName("Casamiento de Ana").amount(PaymentTestSupport.PRICE).mpPreferenceId("pref-1").build());
    }

    private PendingPurchase repurchase(Organizer organizer) {
        return purchases.save(PendingPurchase.builder()
                .organizerId(organizer.getId()).eventName("Cumple de Beto").amount(PaymentTestSupport.PRICE).mpPreferenceId("pref-2").build());
    }

    private void apiReturns(long paymentId, PaymentLookupGateway.PaymentInfo info) {
        when(gateway.findPayment(paymentId)).thenReturn(Optional.of(info));
    }

    private void webhook(long paymentId, int expectedStatus) throws Exception {
        mockMvc.perform(signedWebhook(paymentId)).andExpect(status().is(expectedStatus));
    }

    private void confirmFromReturn(long paymentId) throws Exception {
        mockMvc.perform(post("/api/v1/checkout/confirm").contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentId\":\"" + paymentId + "\"}")).andExpect(status().isNoContent());
    }

    private JsonNode statusOf(UUID reference) throws Exception {
        String body = mockMvc.perform(post("/api/v1/checkout/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalReference\":\"" + reference + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private PaymentIncident onlyIncident() {
        List<PaymentIncident> all = incidents.findAll();
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    // ---------- idempotencia ----------

    @Test
    @DisplayName("Webhook primero y retorno después: una cuenta (con la contraseña elegida), un evento PAID, un mail")
    void webhookThenReturnCreatesEverythingOnce() throws Exception {
        PendingPurchase purchase = newPurchase(EMAIL);
        apiReturns(1001L, payment(1001L, "approved", purchase.getId()));

        webhook(1001L, 200);
        confirmFromReturn(1001L);
        webhook(1001L, 200);

        assertThat(organizers.findAll()).singleElement().satisfies(organizer -> {
            assertThat(organizer.getEmail()).isEqualTo(EMAIL);
            assertThat(organizer.isActivated()).as("usable directamente, sin activación").isTrue();
            assertThat(passwordEncoder.matches(PASSWORD, organizer.getPasswordHash())).isTrue();
        });
        Event event = events.findAll().get(0);
        assertThat(events.count()).isEqualTo(1);
        assertThat(event.getOrigin()).isEqualTo(EventOrigin.PAID);
        assertThat(event.getName()).isEqualTo("Casamiento de Ana");
        verify(emailService, times(1)).send(any());
        assertThat(incidents.count()).isZero();
    }

    @Test
    @DisplayName("Retorno primero y webhook después: el mismo resultado, una sola vez")
    void returnThenWebhookCreatesEverythingOnce() throws Exception {
        PendingPurchase purchase = newPurchase(EMAIL);
        apiReturns(1002L, payment(1002L, "approved", purchase.getId()));

        confirmFromReturn(1002L);
        webhook(1002L, 200);
        confirmFromReturn(1002L);

        assertThat(organizers.count()).isEqualTo(1);
        assertThat(events.count()).isEqualTo(1);
        verify(emailService, times(1)).send(any());
        assertThat(incidents.count()).isZero();
    }

    @Test
    @DisplayName("Procesada: processed_at, mp_payment_id, event_id y organizer_id; SIN email ni hash; estado CREATED sin datos de la cuenta")
    void processedPurchaseIsMinimizedAndStatusRevealsNothing() throws Exception {
        PendingPurchase purchase = newPurchase(EMAIL);
        apiReturns(1003L, payment(1003L, "approved", purchase.getId()));
        webhook(1003L, 200);

        PendingPurchase processed = purchases.findById(purchase.getId()).orElseThrow();
        Organizer organizer = organizers.findAll().get(0);
        Event event = events.findAll().get(0);
        assertThat(processed.getProcessedAt()).isNotNull();
        assertThat(processed.getMpPaymentId()).isEqualTo("1003");
        assertThat(processed.getEventId()).isEqualTo(event.getId());
        assertThat(processed.getOrganizerId()).isEqualTo(organizer.getId());
        assertThat(processed.getEmail()).isNull();
        assertThat(processed.getPasswordHash()).isNull();
        assertThat(processed.getConfirmationEmailSentAt()).isNotNull();

        JsonNode status = statusOf(purchase.getId());
        assertThat(status.get("state").asText()).isEqualTo("CREATED");
        assertThat(status.get("eventName").asText()).isEqualTo("Casamiento de Ana");
        assertThat(status.get("repurchase").asBoolean()).isFalse();
        List<String> fields = new java.util.ArrayList<>();
        status.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("state", "eventName", "repurchase");
        String raw = status.toString();
        assertThat(raw).doesNotContain(EMAIL).doesNotContain(event.getSlug())
                .doesNotContain(event.getId().toString()).doesNotContain(organizer.getId().toString());
    }

    // ---------- se confía solo en la API ----------

    @Test
    @DisplayName("Notificación/retorno con status=approved pero el pago consultado está rejected: nada creado, estado REJECTED")
    void onlyTheApiIsTrusted() throws Exception {
        PendingPurchase purchase = newPurchase(EMAIL);
        apiReturns(1004L, payment(1004L, "rejected", purchase.getId()));

        webhook(1004L, 200);          // el cuerpo firmado dice "status":"approved"
        mockMvc.perform(post("/api/v1/checkout/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentId\":\"1004\",\"status\":\"approved\",\"collection_status\":\"approved\"}"))
                .andExpect(status().isNoContent());

        assertThat(organizers.count()).isZero();
        assertThat(events.count()).isZero();
        assertThat(incidents.count()).isZero();
        verify(emailService, never()).send(any());
        assertThat(statusOf(purchase.getId()).get("state").asText()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("Pendiente / en revisión / autorizado: no crea nada ni incidente; la página sigue en 'confirmando'")
    void pendingStatusesCreateNothing() throws Exception {
        PendingPurchase purchase = newPurchase(EMAIL);
        for (String state : List.of("pending", "in_process", "authorized")) {
            apiReturns(1005L, payment(1005L, state, purchase.getId()));
            webhook(1005L, 200);
            assertThat(statusOf(purchase.getId()).get("state").asText()).isEqualTo("PENDING");
        }
        assertThat(organizers.count()).isZero();
        assertThat(events.count()).isZero();
        assertThat(incidents.count()).isZero();
    }

    // ---------- incidentes ----------

    @Test
    @DisplayName("Monto distinto o moneda distinta -> incidente, nada creado")
    void amountOrCurrencyMismatchIsAnIncident() throws Exception {
        PendingPurchase a = newPurchase(EMAIL);
        PendingPurchase b = newPurchase("otra@test.com");
        apiReturns(1006L, payment(1006L, "approved", "ARS", new BigDecimal("1.00"), a.getId().toString()));
        apiReturns(1007L, payment(1007L, "approved", "USD", PaymentTestSupport.PRICE, b.getId().toString()));

        webhook(1006L, 200);
        webhook(1007L, 200);

        assertThat(organizers.count()).isZero();
        assertThat(events.count()).isZero();
        assertThat(incidents.findByPaymentId("1006").orElseThrow().getReason()).isEqualTo(PaymentIncident.Reason.AMOUNT_MISMATCH);
        assertThat(incidents.findByPaymentId("1007").orElseThrow().getReason()).isEqualTo(PaymentIncident.Reason.CURRENCY_MISMATCH);
        assertThat(statusOf(a.getId()).get("state").asText()).isEqualTo("INCIDENT");
        verify(emailService, never()).send(any());
    }

    @Test
    @DisplayName("Email ya registrado entre la compra y el pago -> incidente; no se toca la cuenta ni su contraseña; la compra guarda el pago")
    void emailRegisteredInBetweenIsAnIncident() throws Exception {
        PendingPurchase purchase = newPurchase(EMAIL);
        String existingHash = passwordEncoder.encode("la-clave-de-la-cuenta-existente");
        Organizer existing = organizers.save(Organizer.builder().email("Compradora@Test.com").passwordHash(existingHash).build());
        apiReturns(1008L, payment(1008L, "approved", purchase.getId()));

        webhook(1008L, 200);
        webhook(1008L, 200);
        confirmFromReturn(1008L);

        assertThat(organizers.count()).isEqualTo(1);
        assertThat(organizers.findById(existing.getId()).orElseThrow().getPasswordHash()).isEqualTo(existingHash);
        assertThat(events.count()).isZero();
        PaymentIncident incident = onlyIncident();
        assertThat(incident.getReason()).isEqualTo(PaymentIncident.Reason.EMAIL_ALREADY_REGISTERED);
        assertThat(incident.getExternalReference()).isEqualTo(purchase.getId().toString());
        PendingPurchase kept = purchases.findById(purchase.getId()).orElseThrow();
        assertThat(kept.getProcessedAt()).isNull();
        assertThat(kept.getMpPaymentId()).as("protege la compra del descarte").isEqualTo("1008");
        assertThat(statusOf(purchase.getId()).get("state").asText()).isEqualTo("INCIDENT");
        verify(emailService, never()).send(any());
    }

    @Test
    @DisplayName("Segundo pago aprobado para una compra ya procesada -> incidente de cobro doble, no un segundo evento")
    void secondPaymentForAProcessedPurchaseIsAnIncident() throws Exception {
        PendingPurchase purchase = newPurchase(EMAIL);
        apiReturns(1009L, payment(1009L, "approved", purchase.getId()));
        apiReturns(1010L, payment(1010L, "approved", purchase.getId()));

        webhook(1009L, 200);
        webhook(1010L, 200);
        webhook(1010L, 200);

        assertThat(events.count()).isEqualTo(1);
        assertThat(organizers.count()).isEqualTo(1);
        PaymentIncident incident = onlyIncident();
        assertThat(incident.getPaymentId()).isEqualTo("1010");
        assertThat(incident.getReason()).isEqualTo(PaymentIncident.Reason.DUPLICATE_PAYMENT);
        assertThat(statusOf(purchase.getId()).get("state").asText()).as("el evento existe").isEqualTo("CREATED");
        verify(emailService, times(1)).send(any());
    }

    @Test
    @DisplayName("external_reference inexistente (o que no es un UUID) -> incidente, uno solo aunque la notificación se repita")
    void unknownReferenceIsRecordedOnce() throws Exception {
        apiReturns(1011L, payment(1011L, "approved", UUID.randomUUID()));
        apiReturns(1012L, payment(1012L, "approved", "ARS", PaymentTestSupport.PRICE, "no-es-una-compra-nuestra"));

        for (int i = 0; i < 3; i++) {
            webhook(1011L, 200);
            webhook(1012L, 200);
        }

        assertThat(incidents.count()).isEqualTo(2);
        assertThat(incidents.findByPaymentId("1011").orElseThrow().getReason()).isEqualTo(PaymentIncident.Reason.UNKNOWN_REFERENCE);
        assertThat(incidents.findByPaymentId("1012").orElseThrow().getReason()).isEqualTo(PaymentIncident.Reason.UNKNOWN_REFERENCE);
        assertThat(organizers.count()).isZero();
        assertThat(events.count()).isZero();
    }

    @Test
    @DisplayName("Un pago ya procesado que vuelve como refunded / charged_back -> incidente, sin acción automática")
    void reversedPaymentIsAnIncident() throws Exception {
        PendingPurchase purchase = newPurchase(EMAIL);
        apiReturns(1013L, payment(1013L, "approved", purchase.getId()));
        webhook(1013L, 200);

        apiReturns(1013L, payment(1013L, "refunded", purchase.getId()));
        webhook(1013L, 200);
        webhook(1013L, 200);

        assertThat(onlyIncident().getReason()).isEqualTo(PaymentIncident.Reason.REFUNDED);
        assertThat(events.count()).as("no se borra nada solo").isEqualTo(1);
    }

    // ---------- recompra ----------

    @Test
    @DisplayName("Recompra -> el evento queda en la cuenta correcta, ninguna cuenta nueva; estado CREATED como recompra")
    void repurchaseCreatesOnlyTheEvent() throws Exception {
        Organizer me = organizers.save(Organizer.builder().email("yo@test.com").passwordHash("hash-yo").build());
        organizers.save(Organizer.builder().email("otro@test.com").passwordHash("hash-otro").build());
        PendingPurchase purchase = repurchase(me);
        apiReturns(1014L, payment(1014L, "approved", purchase.getId()));

        webhook(1014L, 200);
        confirmFromReturn(1014L);

        assertThat(organizers.count()).isEqualTo(2);
        Event event = events.findAll().get(0);
        assertThat(events.count()).isEqualTo(1);
        assertThat(event.getOrganizer().getId()).isEqualTo(me.getId());
        assertThat(event.getOrigin()).isEqualTo(EventOrigin.PAID);
        PendingPurchase processed = purchases.findById(purchase.getId()).orElseThrow();
        assertThat(processed.getOrganizerId()).isEqualTo(me.getId());
        assertThat(processed.getCreatedOrganizer()).isFalse();
        JsonNode status = statusOf(purchase.getId());
        assertThat(status.get("state").asText()).isEqualTo("CREATED");
        assertThat(status.get("repurchase").asBoolean()).isTrue();
        verify(emailService, times(1)).send(any());
    }

    @Test
    @DisplayName("Un evento de cortesía del superadmin sigue saliendo COURTESY")
    void courtesyEventsStayCourtesy() {
        var result = superadminEventService.createFreeEvent("cortesia@test.com", "Evento regalado", "Amigos de la casa");
        assertThat(result.event().getOrigin()).isEqualTo(EventOrigin.COURTESY);
    }

    // ---------- MP caído / pago desconocido ----------

    @Test
    @DisplayName("API de MP caída -> webhook 503 y nada creado; el reintento posterior de MP crea todo bien")
    void gatewayDownThenRetry() throws Exception {
        PendingPurchase purchase = newPurchase(EMAIL);
        when(gateway.findPayment(1015L)).thenThrow(new PaymentGatewayException(new RuntimeException("timeout")));

        webhook(1015L, 503);
        assertThat(organizers.count()).isZero();
        assertThat(events.count()).isZero();
        // El retorno no rompe: "confirmando" igual
        confirmFromReturn(1015L);

        org.mockito.Mockito.reset(gateway);
        apiReturns(1015L, payment(1015L, "approved", purchase.getId()));
        webhook(1015L, 200);

        assertThat(organizers.count()).isEqualTo(1);
        assertThat(events.count()).isEqualTo(1);
        verify(emailService, times(1)).send(any());
    }

    @Test
    @DisplayName("Pago que MP todavía no conoce (404): el webhook reintenta una vez y responde 200 sin crear nada")
    void unknownPaymentIsRetriedOnce() throws Exception {
        when(gateway.findPayment(1016L)).thenReturn(Optional.empty());

        webhook(1016L, 200);

        verify(gateway, times(2)).findPayment(1016L);
        assertThat(incidents.count()).isZero();
        assertThat(organizers.count()).isZero();
    }

    @Test
    @DisplayName("Pago que aparece en el segundo intento del webhook: se procesa en esa misma notificación")
    void paymentVisibleOnTheInternalRetryIsProcessed() throws Exception {
        PendingPurchase purchase = newPurchase(EMAIL);
        when(gateway.findPayment(1017L)).thenReturn(Optional.empty(), Optional.of(payment(1017L, "approved", purchase.getId())));

        webhook(1017L, 200);

        assertThat(events.count()).isEqualTo(1);
    }

    // ---------- mail ----------

    @Test
    @DisplayName("Si el mail falla después del commit, la reserva se libera y la próxima confirmación lo manda (una vez)")
    void failedEmailIsRetriedByTheNextConfirmation() throws Exception {
        PendingPurchase purchase = newPurchase(EMAIL);
        apiReturns(1018L, payment(1018L, "approved", purchase.getId()));
        doThrow(new RuntimeException("Resend caído")).doNothing().when(emailService).send(any());

        webhook(1018L, 200);
        assertThat(events.count()).as("el evento queda creado igual").isEqualTo(1);
        assertThat(purchases.findById(purchase.getId()).orElseThrow().getConfirmationEmailSentAt()).isNull();

        confirmFromReturn(1018L);
        webhook(1018L, 200);

        verify(emailService, times(2)).send(any()); // el que falló + el reintento; ninguno más
        assertThat(purchases.findById(purchase.getId()).orElseThrow().getConfirmationEmailSentAt()).isNotNull();
    }

    @Test
    @DisplayName("El mail va al email de la cuenta, con el link al login y sin la contraseña")
    void emailContent() throws Exception {
        PendingPurchase purchase = newPurchase(EMAIL);
        apiReturns(1019L, payment(1019L, "approved", purchase.getId()));
        org.mockito.ArgumentCaptor<EmailService.EmailMessage> captor = org.mockito.ArgumentCaptor.forClass(EmailService.EmailMessage.class);
        doNothing().when(emailService).send(captor.capture());

        webhook(1019L, 200);

        EmailService.EmailMessage message = captor.getValue();
        assertThat(message.to()).isEqualTo(EMAIL);
        assertThat(message.subject()).isEqualTo("Tu evento está listo");
        assertThat(message.reference()).isEqualTo(purchase.getId().toString());
        assertThat(message.htmlBody()).contains("/admin/login").contains("Casamiento de Ana").doesNotContain(PASSWORD);
    }

    // ---------- logs ----------

    @Test
    @DisplayName("Logs de un flujo completo: ni email, ni hash, ni token, ni secreto del webhook, ni firma, ni cuerpo de MP")
    void logsOfAFullFlowLeakNothing() throws Exception {
        PendingPurchase purchase = newPurchase(EMAIL);
        String hash = purchase.getPasswordHash();
        organizers.save(Organizer.builder().email("tomado@test.com").passwordHash("hash-tomado").build());
        PendingPurchase taken = newPurchase("tomado@test.com");
        apiReturns(1020L, payment(1020L, "approved", purchase.getId()));
        apiReturns(1021L, payment(1021L, "approved", taken.getId()));

        var webhookRequest = signedWebhook(1020L);
        String signature = webhookRequest.buildRequest(new org.springframework.mock.web.MockServletContext()).getHeader("x-signature");
        try (LogCapture logs = LogCapture.start()) {
            mockMvc.perform(webhookRequest).andExpect(status().isOk());
            confirmFromReturn(1020L);
            statusOf(purchase.getId());
            webhook(1021L, 200);

            String text = logs.text();
            assertThat(text).doesNotContain(EMAIL).doesNotContain("tomado@test.com").doesNotContain(hash)
                    .doesNotContain("TEST-token-falso-solo-para-tests").doesNotContain(PaymentTestSupport.WEBHOOK_SECRET)
                    .doesNotContain(signature.substring(signature.indexOf("v1=") + 3)).doesNotContain("cuerpo-secreto-de-mp");
            assertThat(text).contains(purchase.getId().toString()).contains("1020");
        }
    }

    @Test
    @DisplayName("Una compra procesada ya no la toca el descarte, aunque tenga más de 72 hs")
    void processedPurchasesSurviveTheDiscard(@Autowired PendingPurchaseCleanupJob cleanupJob) throws Exception {
        PendingPurchase purchase = newPurchase(EMAIL);
        apiReturns(1022L, payment(1022L, "approved", purchase.getId()));
        webhook(1022L, 200);

        assertThat(cleanupJob.discardStale(Instant.now().plus(java.time.Duration.ofDays(30)))).isZero();
        assertThat(purchases.findById(purchase.getId())).isPresent();
    }
}
