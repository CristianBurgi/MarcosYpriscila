package com.tuapp.eventfoto.superadmin;

import com.tuapp.eventfoto.checkout.PaymentIncident;
import com.tuapp.eventfoto.checkout.PaymentIncidentRepository;
import com.tuapp.eventfoto.checkout.PaymentLookupGateway;
import com.tuapp.eventfoto.checkout.PendingPurchase;
import com.tuapp.eventfoto.checkout.PendingPurchaseRepository;
import com.tuapp.eventfoto.common.email.EmailService;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventLifecycleService;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.message.Message;
import com.tuapp.eventfoto.message.MessageRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.photo.Photo;
import com.tuapp.eventfoto.photo.PhotoRepository;
import com.tuapp.eventfoto.testsupport.LogCapture;
import com.tuapp.eventfoto.testsupport.MutableClock;
import com.tuapp.eventfoto.testsupport.TestEvents;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 9.7-A: listado y acciones del superadmin. Reloj fijo en Argentina; la API de MP y el mail, mockeados.
 * Sin @Transactional: las acciones abren sus propias transacciones (confirmPayment manda el mail después del commit).
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MutableClock.Config.class)
class SuperadminPanelTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);
    private static final BigDecimal PRICE = new BigDecimal("50000.00");
    private static final String SUPERADMIN = "superadmin@boda.com";

    @Autowired private MockMvc mockMvc;
    @Autowired private MutableClock clock;
    @Autowired private EventRepository events;
    @Autowired private OrganizerRepository organizers;
    @Autowired private PhotoRepository photos;
    @Autowired private MessageRepository messages;
    @Autowired private PendingPurchaseRepository purchases;
    @Autowired private PaymentIncidentRepository incidents;
    @Autowired private EventLifecycleService lifecycle;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @MockBean private PaymentLookupGateway gateway;
    @MockBean private EmailService emailService;

    private Organizer organizer;

    @BeforeEach
    void setUp() {
        cleanUp();
        clock.setArgentina(TODAY.atTime(10, 0));
        organizer = organizers.save(Organizer.builder().email("organizadora@test.com").passwordHash("$2a$10$hash-de-prueba").build());
    }

    @AfterEach
    void cleanUp() {
        incidents.deleteAll();
        purchases.deleteAll();
        messages.deleteAll();
        photos.deleteAll();
        events.deleteAll();
        organizers.deleteAll();
    }

    private Event event(String slug, LocalDate date) {
        return events.save(TestEvents.base(organizer, slug).eventDate(date).build());
    }

    private ResultActions postJson(String url, String json) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json).with(user(SUPERADMIN).roles("SUPERADMIN")));
    }

    private String listing(String query) throws Exception {
        return mockMvc.perform(get("/superadmin/eventos" + query).with(user(SUPERADMIN).roles("SUPERADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    // ---------- listado ----------

    @Test
    @DisplayName("Listado: estados calculados, sin activar, cortesía con motivo, extensión, conteos y filtros")
    void listingShowsEveryColumnAndFilters() throws Exception {
        Organizer pending = organizers.save(Organizer.builder().email("sin-activar@test.com").build());
        event("abierto-k7m2xq9p", TODAY);
        events.save(TestEvents.base(pending, "cortesia-k7m2xq9p").name("Cumple de la abuela").eventDate(TODAY.plusDays(10))
                .origin(EventOrigin.COURTESY).originReason("Regalo de cumpleaños").build());
        events.save(TestEvents.base(organizer, "sin-configurar-k7m2xq9p").eventDate(null).wizardCompletedAt(null).build());
        Event expired = event("vencido-k7m2xq9p", TODAY.minusDays(31));
        Event extended = events.save(TestEvents.base(organizer, "extendido-k7m2xq9p").eventDate(TODAY.minusDays(31))
                .retentionOverrideUntil(TODAY.plusDays(20)).build());
        events.save(TestEvents.base(organizer, "borrado-k7m2xq9p").eventDate(TODAY.minusDays(60))
                .purgedAt(Instant.parse("2026-10-01T06:00:00Z")).build());
        photos.save(Photo.builder().event(extended).storageKey("k1").build());
        photos.save(Photo.builder().event(extended).storageKey("k2").build());
        messages.save(Message.builder().event(extended).authorName("Tía").text("Hola").build());

        String all = listing("?scope=ALL");
        assertThat(all).contains("subida abierta", "esperando la fecha", "sin configurar", "vencido", "subida cerrada", "borrado",
                "sin activar", "cortesía", "Regalo de cumpleaños", "extendida", "href=\"/e/abierto-k7m2xq9p\"");
        assertThat(all).doesNotContain("$2a$10$hash-de-prueba");

        String active = listing("");
        assertThat(active).contains("/e/abierto-k7m2xq9p", "/e/extendido-k7m2xq9p", "/e/sin-configurar-k7m2xq9p")
                .doesNotContain("/e/vencido-k7m2xq9p", "/e/borrado-k7m2xq9p");
        String expiredOnly = listing("?scope=EXPIRED");
        assertThat(expiredOnly).contains("/e/vencido-k7m2xq9p", "/e/borrado-k7m2xq9p")
                .doesNotContain("/e/abierto-k7m2xq9p", "/e/extendido-k7m2xq9p", "/e/sin-configurar-k7m2xq9p");
        String courtesy = listing("?scope=ALL&origin=COURTESY");
        assertThat(courtesy).contains("/e/cortesia-k7m2xq9p").doesNotContain("/e/abierto-k7m2xq9p");

        SuperadminPanelService.EventView row = panel().events(SuperadminPanelService.Scope.ALL, null, 0).events().stream()
                .filter(v -> v.event().id().equals(extended.getId())).findFirst().orElseThrow();
        assertThat(row.photos()).isEqualTo(2);
        assertThat(row.messages()).isEqualTo(1);
        assertThat(row.deletionDate()).isEqualTo(TODAY.plusDays(20));
        assertThat(row.status()).isEqualTo(PanelStatus.CLOSED);
        assertThat(expired.getId()).isNotNull();
    }

    @Autowired private SuperadminPanelService panelService;

    private SuperadminPanelService panel() {
        return panelService;
    }

    @Test
    @DisplayName("Rendimiento: el listado hace la misma cantidad de consultas con 3 eventos que con 60")
    void listingRunsAFixedNumberOfQueries() throws Exception {
        for (int i = 0; i < 3; i++) {
            withContent(event("pocos-" + i + "-k7m2xq9p", TODAY));
        }
        long few = statementsFor(() -> listing("?scope=ALL"));
        for (int i = 0; i < 57; i++) {
            Organizer other = organizers.save(Organizer.builder().email("org" + i + "@test.com").build());
            withContent(events.save(TestEvents.base(other, "muchos-" + i + "-k7m2xq9p").eventDate(TODAY.minusDays(i)).build()));
        }
        long many = statementsFor(() -> listing("?scope=ALL"));
        System.out.println("LISTADO consultas: 3 eventos -> " + few + ", 60 eventos (2 páginas) -> " + many);
        assertThat(many).isEqualTo(few);
        assertThat(few).isLessThanOrEqualTo(6);
        assertThat(listing("?scope=ALL")).contains("Siguiente");
    }

    private void withContent(Event event) {
        photos.save(Photo.builder().event(event).storageKey("k-" + event.getId()).build());
        messages.save(Message.builder().event(event).authorName("A").text("B").build());
    }

    private interface Action {
        void run() throws Exception;
    }

    private long statementsFor(Action action) throws Exception {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        action.run();
        return stats.getPrepareStatementCount();
    }

    // ---------- compras pendientes e incidentes ----------

    @Test
    @DisplayName("Compras pendientes: email (nuevo o del organizador), sin password_hash ni la referencia completa")
    void pendingPurchasesExposeNothingSensitive() throws Exception {
        String hash = "$2a$10$abcdefghijklmnopqrstuvHASHSECRETO";
        PendingPurchase fresh = purchases.save(PendingPurchase.builder().email("nueva@test.com").passwordHash(hash)
                .eventName("Casamiento de Ana").amount(PRICE).mpPreferenceId("pref-secreta-1").build());
        PendingPurchase again = purchases.save(PendingPurchase.builder().organizerId(organizer.getId())
                .eventName("Cumple de Beto").amount(PRICE).mpPaymentId("777").lastPaymentStatus("rejected").build());

        String page = listing("");
        assertThat(page).contains("nueva@test.com", "Casamiento de Ana", "organizadora@test.com", "Cumple de Beto",
                "777", "rejected", fresh.getId().toString().substring(0, 8), again.getId().toString().substring(0, 8));
        assertThat(page).doesNotContain(hash, "HASHSECRETO", "pref-secreta-1",
                fresh.getId().toString(), again.getId().toString());

        assertThat(panelService.pendingPurchases()).allSatisfy(row -> {
            assertThat(row.toString()).doesNotContain(hash);
            assertThat(row.reference()).hasSize(8);
        });
    }

    @Test
    @DisplayName("Incidente: se lista con referencia de 8 caracteres; 'Marcar resuelto' pone resolved_at, deja log, repetirlo es idempotente")
    void resolveIncident() throws Exception {
        String reference = UUID.randomUUID().toString();
        PaymentIncident incident = incidents.save(PaymentIncident.builder().id(UUID.randomUUID()).paymentId("123456")
                .externalReference(reference).reason(PaymentIncident.Reason.AMOUNT_MISMATCH).createdAt(Instant.now()).build());

        assertThat(listing("")).contains("AMOUNT_MISMATCH", "123456", reference.substring(0, 8)).doesNotContain(reference);

        try (LogCapture logs = LogCapture.start()) {
            postJson("/api/v1/superadmin/incidents/" + incident.getId() + "/resolve", "{}")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.resolvedNow").value(true));
            assertThat(logs.text()).contains("[SUPERADMIN] " + SUPERADMIN + " resolver-incidente incidente " + incident.getId() + " → resuelto");
        }
        assertThat(incidents.findById(incident.getId()).orElseThrow().getResolvedAt()).isNotNull();
        postJson("/api/v1/superadmin/incidents/" + incident.getId() + "/resolve", "{}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.resolvedNow").value(false));
        postJson("/api/v1/superadmin/incidents/" + UUID.randomUUID() + "/resolve", "{}").andExpect(status().isNotFound());
        assertThat(listing("")).doesNotContain("AMOUNT_MISMATCH");
    }

    // ---------- extender vigencia ----------

    private String retention(Event event) {
        return "/api/v1/superadmin/events/" + event.getId() + "/retention";
    }

    @Test
    @DisplayName("Extender: antes de hoy o más de un año después del borrado original -> 400; evento borrado -> 409")
    void retentionValidation() throws Exception {
        Event event = event("prueba-k7m2xq9p", TODAY.minusDays(10)); // borrado original: TODAY + 20
        postJson(retention(event), "{\"until\":\"" + TODAY.minusDays(1) + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("La fecha no puede ser anterior a hoy."));
        postJson(retention(event), "{\"until\":\"" + TODAY.plusDays(20).plusYears(1).plusDays(1) + "\"}")
                .andExpect(status().isBadRequest());
        postJson(retention(event), "{}").andExpect(status().isBadRequest());
        postJson(retention(event), "{\"until\":\"no-es-fecha\"}").andExpect(status().isBadRequest());
        assertThat(events.findById(event.getId()).orElseThrow().getRetentionOverrideUntil()).isNull();

        postJson(retention(event), "{\"until\":\"" + TODAY.plusDays(20).plusYears(1) + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.deletionDate").value(TODAY.plusDays(20).plusYears(1).toString()));

        Event purged = events.save(TestEvents.base(organizer, "borrado-k7m2xq9p").eventDate(TODAY.minusDays(60))
                .purgedAt(Instant.parse("2026-10-01T06:00:00Z")).build());
        try (LogCapture logs = LogCapture.start()) {
            postJson(retention(purged), "{\"until\":\"" + TODAY.plusDays(30) + "\"}")
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("El álbum ya se borró: no se puede extender."));
            assertThat(logs.text()).contains("[SUPERADMIN] " + SUPERADMIN + " extender-vigencia evento " + purged.getId(), "→ rechazado 409");
        }
        assertThat(events.findById(purged.getId()).orElseThrow().getRetentionOverrideUntil()).isNull();
    }

    @Test
    @DisplayName("Extender con el recordatorio ya enviado: se reinicia y el job manda otro 5 días antes de la fecha nueva")
    void retentionResetsTheReminder() throws Exception {
        Event event = event("prueba-k7m2xq9p", TODAY.minusDays(27)); // se borra en 3 días: el recordatorio ya sale
        assertThat(lifecycle.run("test").orElseThrow().reminders()).isEqualTo(1);
        verify(emailService, times(1)).send(any());
        assertThat(events.findById(event.getId()).orElseThrow().getExpiryReminderSentAt()).isNotNull();

        LocalDate newDate = TODAY.plusDays(30);
        postJson(retention(event), "{\"until\":\"" + newDate + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.reminderReset").value(true));
        assertThat(events.findById(event.getId()).orElseThrow().getExpiryReminderSentAt()).isNull();

        clearInvocations(emailService);
        assertThat(lifecycle.run("test").orElseThrow().reminders()).as("hoy: faltan 30 días").isZero();
        clock.setArgentina(newDate.minusDays(5).atTime(3, 0));
        assertThat(lifecycle.run("test").orElseThrow()).isEqualTo(new EventLifecycleService.RunResult(1, 0, 0));
        verify(emailService, times(1)).send(any());
    }

    @Test
    @DisplayName("Extender un evento vencido sin borrar: vuelve a 'subida cerrada' y los invitados lo ven de nuevo")
    void retentionUnexpiresAnExpiredEvent() throws Exception {
        Event event = event("vencido-k7m2xq9p", TODAY.minusDays(31));
        mockMvc.perform(get("/api/v1/events/" + event.getSlug())).andExpect(status().isGone());
        assertThat(listing("?scope=EXPIRED")).contains("/e/vencido-k7m2xq9p", "vencido");

        postJson(retention(event), "{\"until\":\"" + TODAY.plusDays(15) + "\"}").andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/events/" + event.getSlug())).andExpect(status().isOk());
        assertThat(listing("?scope=EXPIRED")).doesNotContain("/e/vencido-k7m2xq9p");
        assertThat(listing("")).contains("/e/vencido-k7m2xq9p", "subida cerrada", "extendida");
    }

    // ---------- marcar pago aprobado ----------

    private PendingPurchase newPurchase() {
        return purchases.save(PendingPurchase.builder().email("compradora@test.com").passwordHash("$2a$10$otro-hash")
                .eventName("Casamiento de Ana").amount(PRICE).mpPreferenceId("pref-1").build());
    }

    private void mpReturns(long id, String status, UUID reference) {
        when(gateway.findPayment(id)).thenReturn(Optional.of(
                new PaymentLookupGateway.PaymentInfo(id, status, "ARS", PRICE, BigDecimal.ZERO, reference.toString())));
    }

    @Test
    @DisplayName("Marcar pago aprobado con MP diciendo 'rechazado': NOT_APPROVED y no se crea nada")
    void confirmPaymentNotApproved() throws Exception {
        PendingPurchase purchase = newPurchase();
        mpReturns(5001L, "rejected", purchase.getId());
        long organizersBefore = organizers.count();

        postJson("/api/v1/superadmin/payments/confirm", "{\"paymentId\":\"5001\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("NOT_APPROVED"));

        verify(gateway).findPayment(5001L);
        assertThat(organizers.count()).isEqualTo(organizersBefore);
        assertThat(events.count()).isZero();
        assertThat(purchases.findById(purchase.getId()).orElseThrow().getProcessedAt()).isNull();
        verify(emailService, never()).send(any());
    }

    @Test
    @DisplayName("Marcar pago aprobado: pasa por confirmPayment (CREATED), repetirlo da ALREADY_PROCESSED; formato inválido -> 400")
    void confirmPaymentApprovedThenRepeated() throws Exception {
        PendingPurchase purchase = newPurchase();
        mpReturns(5002L, "approved", purchase.getId());

        try (LogCapture logs = LogCapture.start()) {
            postJson("/api/v1/superadmin/payments/confirm", "{\"paymentId\":\"5002\"}")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("CREATED"));
            assertThat(logs.text()).contains("[SUPERADMIN] " + SUPERADMIN + " marcar-pago-aprobado payment_id 5002 → CREATED");
        }
        assertThat(events.findAll()).singleElement().satisfies(e -> assertThat(e.getOrigin()).isEqualTo(EventOrigin.PAID));
        postJson("/api/v1/superadmin/payments/confirm", "{\"paymentId\":\"5002\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("ALREADY_PROCESSED"));
        assertThat(events.count()).isEqualTo(1);

        postJson("/api/v1/superadmin/payments/confirm", "{\"paymentId\":\"abc\"}").andExpect(status().isBadRequest());
        when(gateway.findPayment(5003L)).thenReturn(Optional.empty());
        postJson("/api/v1/superadmin/payments/confirm", "{\"paymentId\":\"5003\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("UNKNOWN_PAYMENT"));
    }

    // ---------- reenviar mail de confirmación ----------

    @Test
    @DisplayName("Reenviar mail: solo en eventos que salieron de una compra (botón en la fila); reenvía al organizador")
    void resendConfirmation() throws Exception {
        PendingPurchase purchase = newPurchase();
        mpReturns(5004L, "approved", purchase.getId());
        postJson("/api/v1/superadmin/payments/confirm", "{\"paymentId\":\"5004\"}").andExpect(jsonPath("$.outcome").value("CREATED"));
        Event bought = events.findAll().get(0);
        Event courtesy = event("cortesia-k7m2xq9p", TODAY.plusDays(5));

        String page = listing("?scope=ALL");
        assertThat(page).contains("data-action=\"resend-mail\"");
        assertThat(page.split("data-action=\"resend-mail\"", -1)).as("un solo botón").hasSize(2);
        assertThat(page).containsPattern("data-action=\"resend-mail\"[^>]*data-id=\"" + bought.getId() + "\"");

        clearInvocations(emailService);
        postJson("/api/v1/superadmin/events/" + bought.getId() + "/resend-confirmation", "{}").andExpect(status().isOk());
        var mail = org.mockito.ArgumentCaptor.forClass(EmailService.EmailMessage.class);
        verify(emailService).send(mail.capture());
        assertThat(mail.getValue().to()).isEqualTo("compradora@test.com");
        assertThat(mail.getValue().subject()).isEqualTo("Tu evento está listo");
        assertThat(purchases.findById(purchase.getId()).orElseThrow().getConfirmationEmailSentAt()).isNotNull();

        postJson("/api/v1/superadmin/events/" + courtesy.getId() + "/resend-confirmation", "{}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Ese evento no salió de una compra."));
    }
}
