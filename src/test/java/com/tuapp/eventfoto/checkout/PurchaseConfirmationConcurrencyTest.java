package com.tuapp.eventfoto.checkout;

import com.tuapp.eventfoto.common.email.EmailService;
import com.tuapp.eventfoto.event.EventCreationService;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.testsupport.PostgresTestCredentials;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.tuapp.eventfoto.checkout.PaymentTestSupport.payment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fase 9.4: concurrencia REAL contra Postgres (H2 no reproduce igual SELECT ... FOR UPDATE ni la transacción abortada
 * por una violación de unicidad). Base descartable migrada con Flyway (ddl validate). Sin @Transactional: cada camino
 * abre sus propias transacciones, como en producción. En local se omite sin Postgres; en CI sin Postgres FALLA.
 *
 * <p>La creación del evento se demora a propósito (spy sobre EventCreationService) para que los dos caminos se pisen:
 * sin el bloqueo de fila, los dos leerían la compra sin procesar y crearían dos veces.
 */
@SpringBootTest
@ActiveProfiles("test")
class PurchaseConfirmationConcurrencyTest {

    private static final String PASSWORD = "una-clave-larga-y-rara-93";
    private static String adminUrl;
    private static String user;
    private static String password;
    private static String throwawayDb;
    private static String throwawayUrl;

    @BeforeAll
    static void createDatabase() throws SQLException {
        PostgresTestCredentials.Credentials creds = PostgresTestCredentials.resolveOrNull();
        if (creds == null) {
            if (PostgresTestCredentials.isCi()) {
                fail("CI=true pero faltan DB_URL/DB_USER/DB_PASSWORD de Postgres: este test no puede saltearse en CI.");
            }
            assumeTrue(false, "Sin credenciales de Postgres: se omite (solo en local)");
        }
        adminUrl = creds.url();
        user = creds.user();
        password = creds.password();
        try (Connection ignored = DriverManager.getConnection(adminUrl, user, password)) {
            // alcanzable
        } catch (SQLException e) {
            if (PostgresTestCredentials.isCi()) {
                fail("CI=true pero Postgres no responde.");
            }
            assumeTrue(false, "Postgres local no responde: se omite (solo en local)");
        }
        throwawayDb = "eventfoto_concurrency_" + System.nanoTime();
        try (Connection conn = DriverManager.getConnection(adminUrl, user, password); Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE " + throwawayDb);
        }
        Matcher m = Pattern.compile("(jdbc:postgresql://[^/]+/)([^?]+)(.*)").matcher(adminUrl);
        if (!m.matches()) {
            throw new IllegalArgumentException("No se pudo parsear DB_URL");
        }
        throwawayUrl = m.group(1) + throwawayDb + m.group(3);
    }

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> throwawayUrl);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        if (throwawayDb == null) {
            return;
        }
        try (Connection conn = DriverManager.getConnection(adminUrl, user, password); Statement st = conn.createStatement()) {
            st.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + throwawayDb + "' AND pid <> pg_backend_pid()");
            st.execute("DROP DATABASE IF EXISTS " + throwawayDb);
        }
    }

    @Autowired private PurchaseConfirmationService confirmationService;
    @Autowired private PendingPurchaseRepository purchases;
    @Autowired private PaymentIncidentRepository incidents;
    @Autowired private OrganizerRepository organizers;
    @Autowired private EventRepository events;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockBean private PaymentLookupGateway gateway;
    @MockBean private EmailService emailService;
    @SpyBean private EventCreationService eventCreationService;

    @BeforeEach
    void slowDownEventCreation() {
        cleanUp();
        doAnswer(invocation -> {
            Thread.sleep(700);
            return invocation.callRealMethod();
        }).when(eventCreationService).createEvent(any(), any(), any(), any());
    }

    @AfterEach
    void cleanUp() {
        incidents.deleteAll();
        purchases.deleteAll();
        events.deleteAll();
        organizers.deleteAll();
    }

    /** Corre las dos llamadas a la vez y devuelve los resultados; falla si alguna tiró una excepción. */
    private List<PurchaseConfirmationService.Outcome> concurrently(Callable<PurchaseConfirmationService.Outcome> a,
                                                                   Callable<PurchaseConfirmationService.Outcome> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<PurchaseConfirmationService.Outcome> fa = pool.submit(() -> { start.await(); return a.call(); });
            Future<PurchaseConfirmationService.Outcome> fb = pool.submit(() -> { start.await(); return b.call(); });
            start.countDown();
            return List.of(fa.get(), fb.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Webhook y retorno a la vez para la misma compra nueva -> una cuenta, un evento, un mail")
    void sameNewPurchaseConfirmedTwiceAtOnceCreatesOnce() throws Exception {
        PendingPurchase purchase = purchases.save(PendingPurchase.builder()
                .email("simultanea@test.com").passwordHash(passwordEncoder.encode(PASSWORD))
                .eventName("Evento simultáneo").amount(PaymentTestSupport.PRICE).mpPreferenceId("pref").build());
        when(gateway.findPayment(7001L)).thenReturn(Optional.of(payment(7001L, "approved", purchase.getId())));

        List<PurchaseConfirmationService.Outcome> outcomes = concurrently(
                () -> confirmationService.confirmPayment("7001"), () -> confirmationService.confirmPayment("7001"));

        assertThat(outcomes).containsExactlyInAnyOrder(PurchaseConfirmationService.Outcome.CREATED, PurchaseConfirmationService.Outcome.ALREADY_PROCESSED);
        assertThat(organizers.count()).isEqualTo(1);
        assertThat(events.count()).isEqualTo(1);
        assertThat(incidents.count()).isZero();
        verify(emailService, times(1)).send(any());
    }

    @Test
    @DisplayName("Webhook y retorno a la vez para la misma recompra -> un solo evento (sin el FOR UPDATE se duplicaría)")
    void sameRepurchaseConfirmedTwiceAtOnceCreatesOnce() throws Exception {
        Organizer organizer = organizers.save(Organizer.builder().email("recompra@test.com").passwordHash("hash").build());
        PendingPurchase purchase = purchases.save(PendingPurchase.builder()
                .organizerId(organizer.getId()).eventName("Segundo evento").amount(PaymentTestSupport.PRICE).mpPreferenceId("pref").build());
        when(gateway.findPayment(7002L)).thenReturn(Optional.of(payment(7002L, "approved", purchase.getId())));

        List<PurchaseConfirmationService.Outcome> outcomes = concurrently(
                () -> confirmationService.confirmPayment("7002"), () -> confirmationService.confirmPayment("7002"));

        assertThat(outcomes).containsExactlyInAnyOrder(PurchaseConfirmationService.Outcome.CREATED, PurchaseConfirmationService.Outcome.ALREADY_PROCESSED);
        assertThat(events.count()).isEqualTo(1);
        verify(emailService, times(1)).send(any());
    }

    @Test
    @DisplayName("Dos compras distintas con el mismo email, a la vez -> una cuenta; la otra con mp_payment_id e incidente; ninguna transacción rota")
    void twoPurchasesSameEmailAtOnce() throws Exception {
        PendingPurchase first = purchases.save(PendingPurchase.builder()
                .email("mismo@test.com").passwordHash(passwordEncoder.encode(PASSWORD))
                .eventName("Primera").amount(PaymentTestSupport.PRICE).mpPreferenceId("pref-a").build());
        PendingPurchase second = purchases.save(PendingPurchase.builder()
                .email("mismo@test.com").passwordHash(passwordEncoder.encode("otra-clave-larga-y-rara-27"))
                .eventName("Segunda").amount(PaymentTestSupport.PRICE).mpPreferenceId("pref-b").build());
        when(gateway.findPayment(7003L)).thenReturn(Optional.of(payment(7003L, "approved", first.getId())));
        when(gateway.findPayment(7004L)).thenReturn(Optional.of(payment(7004L, "approved", second.getId())));

        List<PurchaseConfirmationService.Outcome> outcomes = concurrently(
                () -> confirmationService.confirmPayment("7003"), () -> confirmationService.confirmPayment("7004"));

        assertThat(outcomes).as("las dos terminan sin excepción (ninguna 'current transaction is aborted')")
                .containsExactlyInAnyOrder(PurchaseConfirmationService.Outcome.CREATED, PurchaseConfirmationService.Outcome.INCIDENT);
        assertThat(organizers.count()).isEqualTo(1);
        assertThat(events.count()).isEqualTo(1);

        PendingPurchase a = purchases.findById(first.getId()).orElseThrow();
        PendingPurchase b = purchases.findById(second.getId()).orElseThrow();
        PendingPurchase loser = a.getProcessedAt() == null ? a : b;
        PendingPurchase winner = loser == a ? b : a;
        assertThat(winner.getProcessedAt()).isNotNull();
        assertThat(loser.getProcessedAt()).isNull();
        assertThat(loser.getMpPaymentId()).isNotNull();
        assertThat(loser.getEmail()).as("conserva los datos para la revisión manual").isEqualTo("mismo@test.com");
        assertThat(incidents.findAll()).singleElement().satisfies(incident -> {
            assertThat(incident.getReason()).isEqualTo(PaymentIncident.Reason.EMAIL_ALREADY_REGISTERED);
            assertThat(incident.getExternalReference()).isEqualTo(loser.getId().toString());
            assertThat(incident.getPaymentId()).isEqualTo(loser.getMpPaymentId());
        });
        verify(emailService, times(1)).send(any());
    }
}
