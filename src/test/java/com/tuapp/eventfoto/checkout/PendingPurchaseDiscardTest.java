package com.tuapp.eventfoto.checkout;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Descarte de pendientes: solo lo no procesado, sin pago de MP y vencido (24 hs de preferencia + 48 hs de margen).
 * Sin @Transactional: el DELETE del repositorio abre su propia transacción, como en producción.
 */
@SpringBootTest
@ActiveProfiles("test")
class PendingPurchaseDiscardTest {

    @Autowired private CheckoutService checkoutService;
    @Autowired private PendingPurchaseRepository purchases;
    @MockBean private PaymentPreferenceGateway gateway;

    private final Instant now = Instant.now();

    @AfterEach
    void cleanUp() {
        purchases.deleteAll();
    }

    private UUID purchase(Duration age, String preferenceId, Instant processedAt, String paymentId) {
        return purchases.save(PendingPurchase.builder()
                .email("descarte-" + UUID.randomUUID() + "@test.com").passwordHash("hash")
                .eventName("Evento").amount(new BigDecimal("50000.00"))
                .createdAt(now.minus(age)).mpPreferenceId(preferenceId)
                .processedAt(processedAt).mpPaymentId(paymentId).build()).getId();
    }

    @Test
    @DisplayName("Se descartan las no procesadas y vencidas; una procesada, o con mp_payment_id, no se toca")
    void discardsOnlyUnprocessedExpiredPurchases() {
        UUID expired = purchase(Duration.ofHours(73), "pref-1", null, null);
        UUID processed = purchase(Duration.ofHours(200), "pref-2", now.minus(Duration.ofHours(100)), null);
        UUID withPayment = purchase(Duration.ofHours(200), "pref-3", null, "98765");
        UUID processedWithPayment = purchase(Duration.ofHours(200), "pref-4", now.minus(Duration.ofHours(100)), "98766");

        int discarded = checkoutService.discardStale(now);

        assertThat(discarded).isEqualTo(1);
        assertThat(purchases.findById(expired)).isEmpty();
        assertThat(purchases.findById(processed)).isPresent();
        assertThat(purchases.findById(withPayment)).isPresent();
        assertThat(purchases.findById(processedWithPayment)).isPresent();
    }

    @Test
    @DisplayName("Dentro del margen (< 72 hs) no se descarta: a un pago iniciado al final todavía le puede llegar la aprobación")
    void keepsPurchasesInsideTheSafetyMargin() {
        UUID justCreated = purchase(Duration.ofMinutes(1), "pref-1", null, null);
        UUID dayOld = purchase(Duration.ofHours(25), "pref-2", null, null);
        UUID insideMargin = purchase(Duration.ofHours(71), "pref-3", null, null);

        assertThat(checkoutService.discardStale(now)).isZero();
        assertThat(purchases.findAllById(java.util.List.of(justCreated, dayOld, insideMargin))).hasSize(3);
    }

    @Test
    @DisplayName("Sin preferencia: se descarta pasado el plazo de gracia, pero no una compra que se está creando ahora")
    void discardsPurchasesWithoutPreferenceAfterTheGracePeriod() {
        UUID inFlight = purchase(Duration.ofMinutes(1), null, null, null);
        UUID failedAttempt = purchase(Duration.ofMinutes(20), null, null, null);

        assertThat(checkoutService.discardStale(now)).isEqualTo(1);
        assertThat(purchases.findById(inFlight)).isPresent();
        assertThat(purchases.findById(failedAttempt)).isEmpty();
    }

    @Test
    @DisplayName("Sin preferencia pero ya procesada o con pago de MP: tampoco se toca")
    void neverDiscardsProcessedOrPaidEvenWithoutPreference() {
        UUID paid = purchase(Duration.ofHours(5), null, null, "55555");
        UUID processed = purchase(Duration.ofHours(5), null, now.minus(Duration.ofHours(1)), null);

        assertThat(checkoutService.discardStale(now)).isZero();
        assertThat(purchases.findAllById(java.util.List.of(paid, processed))).hasSize(2);
    }
}
