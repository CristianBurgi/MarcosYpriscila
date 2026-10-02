package com.tuapp.eventfoto.realtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Fase 9.1 Bloque 4: test de estrés de la carrera entre alta y baja de emitters sobre el MISMO
 * evento. Antes, la limpieza (remove + isEmpty + remove(eventId)) y el alta (computeIfAbsent + add)
 * no eran atómicas: un cliente que se suscribía justo cuando se desconectaba el último quedaba en una
 * lista que se quitaba del mapa y nunca recibía un evento (reconexiones masivas: un salón que pierde
 * señal).
 *
 * Cada ronda arranca dos hilos a la vez desde una barrera: uno dispara la desconexión del único
 * emitter vivo (el callback de limpieza real) y el otro suscribe un emitter nuevo. Al final, el
 * emitter nuevo TIENE que estar registrado y recibir un broadcast.
 */
class SseBroadcasterConcurrencyTest {

    private static final int ROUNDS = 20_000;

    @Test
    @DisplayName("Alta y desconexión simultáneas sobre el mismo evento: ningún suscriptor nuevo queda huérfano")
    void newSubscriberIsNeverOrphanedByAConcurrentDisconnect() throws Exception {
        SseBroadcaster broadcaster = new SseBroadcaster();
        UUID eventId = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger orphaned = new AtomicInteger();
        try {
            for (int round = 0; round < ROUNDS; round++) {
                SseEmitter leaving = mock(SseEmitter.class);
                broadcaster.register(eventId, leaving);
                ArgumentCaptor<Runnable> onCompletion = ArgumentCaptor.forClass(Runnable.class);
                verify(leaving).onCompletion(onCompletion.capture());

                SseEmitter arriving = mock(SseEmitter.class);
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);
                List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
                tasks.add(pool.submit(() -> { ready.countDown(); await(go); onCompletion.getValue().run(); }));
                tasks.add(pool.submit(() -> { ready.countDown(); await(go); broadcaster.register(eventId, arriving); }));
                ready.await();
                go.countDown();
                for (java.util.concurrent.Future<?> task : tasks) {
                    task.get(10, TimeUnit.SECONDS);
                }

                if (broadcaster.getActiveSubscribersCount(eventId) != 1) {
                    orphaned.incrementAndGet();
                }
                ArgumentCaptor<Runnable> arrivingCleanup = ArgumentCaptor.forClass(Runnable.class);
                verify(arriving).onCompletion(arrivingCleanup.capture());

                // El suscriptor nuevo debe recibir un broadcast real.
                clearInvocations(arriving);
                broadcaster.broadcastMessageDeleted(eventId, UUID.randomUUID());
                try {
                    verify(arriving, atLeastOnce()).send(any(SseEmitter.SseEventBuilder.class));
                } catch (AssertionError notDelivered) {
                    orphaned.incrementAndGet();
                }

                // Limpieza de la ronda: se desconecta el suscriptor nuevo.
                arrivingCleanup.getValue().run();
            }
        } finally {
            pool.shutdownNow();
        }

        System.out.println("SSE-RACE rondas=" + ROUNDS + " suscriptores_huerfanos=" + orphaned.get());
        assertThat(orphaned.get()).as("suscriptores nuevos que quedaron huérfanos (sin estar registrados o sin recibir el broadcast)").isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
