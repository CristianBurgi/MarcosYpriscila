package com.tuapp.eventfoto.demo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cada 5 minutos borra las demos de más de 30 minutos (DemoService.purgeExpired): R2 bajo demo/{sid}/, después sus
 * filas. Una demo que falla no frena al resto y se reintenta en la próxima corrida. El log lleva cantidades, no sids.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoCleanupJob {

    private final DemoService demoService;

    // ponytail: una sola réplica en Railway; con más, un lock en la base (como EventLifecycleService).
    private final AtomicBoolean running = new AtomicBoolean();

    @Scheduled(initialDelay = 60_000, fixedDelayString = "${app.demo.cleanup-interval-ms:300000}")
    public void run() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            DemoService.PurgeResult result = demoService.purgeExpired();
            if (result.sessions() > 0 || result.failed() > 0) {
                log.info("Demo: limpieza -- {} demo(s) borrada(s), {} objeto(s) en storage, {} con error",
                        result.sessions(), result.objects(), result.failed());
            }
        } finally {
            running.set(false);
        }
    }
}
