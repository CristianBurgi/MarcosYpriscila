package com.tuapp.eventfoto.checkout;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Job y no chequeo al vuelo: es limpieza sin ningún camino de usuario y el DELETE es idempotente, así que sirve igual
 * si hay más de una instancia. Qué se descarta y por qué: ver {@link CheckoutService#discardStale}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.checkout.enabled", havingValue = "true")
public class PendingPurchaseCleanupJob {

    private final CheckoutService checkoutService;

    @Scheduled(initialDelay = 60_000, fixedDelayString = "${app.checkout.cleanup-interval-ms:3600000}")
    public void run() {
        try {
            int discarded = checkoutService.discardStale(Instant.now());
            if (discarded > 0) {
                log.info("Compras pendientes descartadas: {}", discarded);
            }
        } catch (RuntimeException e) {
            log.warn("Falló la limpieza de compras pendientes: {}", e.getClass().getSimpleName());
        }
    }
}
