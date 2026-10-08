package com.tuapp.eventfoto.common.config;

import com.tuapp.eventfoto.common.exception.RateLimitExceededException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterServiceTest {

    private final RateLimiterService limiter = new RateLimiterService();

    @Test
    void elPedidoNMas1SeRechaza() {
        for (int i = 0; i < 5; i++) {
            limiter.checkAdminLoginRateLimit("198.51.100.1");
        }
        assertThatThrownBy(() -> limiter.checkAdminLoginRateLimit("198.51.100.1"))
                .isInstanceOf(RateLimitExceededException.class);
        // Otra IP tiene su propio cupo.
        limiter.checkAdminLoginRateLimit("198.51.100.2");
    }

    @Test
    void conPedidosSimultaneosPasanExactamenteN() throws Exception {
        int threads = 64; // límite por guestToken de upload-url: 30
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            // Muchas rondas, cada una con su invitado: una carrera entre contar y anotar aparece en alguna.
            for (int round = 0; round < 200; round++) {
                String guestToken = "invitado-" + round;
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Boolean>> results = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    results.add(pool.submit(() -> {
                        go.await();
                        try {
                            limiter.checkUploadUrlRateLimit("198.51.100.3", guestToken);
                            return true;
                        } catch (RateLimitExceededException e) {
                            return false;
                        }
                    }));
                }
                go.countDown();
                int allowed = 0;
                for (Future<Boolean> r : results) {
                    if (r.get(5, TimeUnit.SECONDS)) {
                        allowed++;
                    }
                }
                assertThat(allowed).as("ronda %d", round).isEqualTo(30);
                limiter.resetRateLimits(); // que la capa por IP (500/min) no se meta entre rondas
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
