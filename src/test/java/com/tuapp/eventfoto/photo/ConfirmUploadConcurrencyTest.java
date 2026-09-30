package com.tuapp.eventfoto.photo;

import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.photo.dto.ConfirmUploadRequestDTO;
import com.tuapp.eventfoto.storage.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Repite el escenario del Bloque 3 (Test 9/10): R2 lento + 50 confirmaciones
 * concurrentes. Ahora que validateAndConvertIfNeeded() ocurre FUERA de la
 * transacción, la latencia de storage no retiene conexiones de HikariCP.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConfirmUploadConcurrencyTest {

    private static final int CONCURRENT = 50;
    private static final Duration SLOW_STORAGE = Duration.ofMillis(400);

    @Autowired
    private PhotoService photoService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private PhotoRepository photoRepository;

    @Autowired
    private GuestQuotaRepository guestQuotaRepository;

    @Autowired
    private OrganizerRepository organizerRepository;

    @MockBean
    private StorageService storageService;

    private Event event;

    @BeforeEach
    void setUp() {
        photoRepository.deleteAll();
        guestQuotaRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();

        Organizer organizer = organizerRepository.save(Organizer.builder().email("concurrency@test.com").build());

        event = eventRepository.save(Event.builder()
                .organizer(organizer)
                .name("Boda de Marcos y Priscila")
                .slug("evento-demo-k7m2xq9p")
                .eventDate(LocalDate.now().plusDays(1))
                .uploadDeadline(Instant.now().plusSeconds(864000))
                .isActive(true)
                .origin(EventOrigin.PAID)
                .build());

        // streamObject lento (simula R2 degradado) + firma JPEG válida
        when(storageService.streamObject(any())).thenAnswer(inv -> {
            Thread.sleep(SLOW_STORAGE.toMillis());
            return new ByteArrayInputStream(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        });
        when(storageService.generatePublicUrl(any())).thenReturn("https://r2.test/public.jpg");
    }

    @AfterEach
    void tearDown() {
        photoRepository.deleteAll();
        guestQuotaRepository.deleteAll();
        eventRepository.deleteAll();
    }

    @Test
    void fiftyConcurrentConfirmsWithSlowStorage() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        long start = System.nanoTime();
        List<Future<?>> futures = IntStream.range(0, CONCURRENT).<Future<?>>mapToObj(i -> pool.submit(() -> {
            try {
                ConfirmUploadRequestDTO req = new ConfirmUploadRequestDTO(
                        "photos/evento-demo-k7m2xq9p/concurrent-" + i + ".jpg", "Invitado " + i, null, "guest-token-" + i);
                photoService.confirmUpload("evento-demo-k7m2xq9p", req);
                ok.incrementAndGet();
            } catch (Exception e) {
                failed.incrementAndGet();
                e.printStackTrace();
            }
        })).toList();

        pool.shutdown();
        boolean finished = pool.awaitTermination(60, TimeUnit.SECONDS);
        for (Future<?> f : futures) {
            f.get();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("[Test 9/10 repetido] %d confirms concurrentes, storage lento %dms -> ok=%d failed=%d en %dms%n",
                CONCURRENT, SLOW_STORAGE.toMillis(), ok.get(), failed.get(), elapsedMs);

        assertTrue(finished, "El pool no terminó en 60s (posible deadlock / pool agotado)");
        assertEquals(CONCURRENT, ok.get(), "Todas las confirmaciones deben tener éxito");
        assertEquals(0, failed.get());
        assertEquals(CONCURRENT, photoRepository.count(), "Deben persistirse las 50 fotos");
        // Con la conversión/lectura fuera de la transacción, el tiempo total lo domina la
        // latencia de storage (~400ms) y no la contención del pool de 10 conexiones.
        assertTrue(elapsedMs < 15_000, "Tardó demasiado (" + elapsedMs + "ms): sugiere contención de conexiones");
    }
}
