package com.tuapp.eventfoto.checkout;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Job y no chequeo al vuelo: es limpieza sin ningún camino de usuario y el DELETE es idempotente, así que sirve igual
 * si hay más de una instancia. Corre SIEMPRE, con el checkout prendido o apagado: la limpieza no depende de Mercado
 * Pago, y apagar el checkout no puede dejar para siempre compras viejas con email y hash de contraseña.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingPurchaseCleanupJob {

    /** Margen sobre el vencimiento antes de descartar: un pago iniciado a último momento todavía puede aprobarse. */
    static final Duration DISCARD_MARGIN = Duration.ofHours(48);
    /** Una compra sin preferencia más vieja que esto es un intento que falló (el pedido a MP dura segundos). */
    static final Duration ORPHAN_GRACE = Duration.ofMinutes(15);

    private final PendingPurchaseRepository purchases;

    @Scheduled(initialDelay = 60_000, fixedDelayString = "${app.checkout.cleanup-interval-ms:3600000}")
    public void run() {
        try {
            int discarded = discardStale(Instant.now());
            if (discarded > 0) {
                log.info("Compras pendientes descartadas: {}", discarded);
            }
        } catch (RuntimeException e) {
            log.warn("Falló la limpieza de compras pendientes: {}", e.getClass().getSimpleName());
        }
    }

    /** Ver {@link PendingPurchaseRepository#discard}. Devuelve cuántas compras se descartaron. */
    public int discardStale(Instant now) {
        return purchases.discard(now.minus(ORPHAN_GRACE), now.minus(CheckoutService.PREFERENCE_TTL).minus(DISCARD_MARGIN));
    }
}
