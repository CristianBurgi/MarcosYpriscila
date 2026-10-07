package com.tuapp.eventfoto.photo;

import com.tuapp.eventfoto.event.UploadWindow;
import com.tuapp.eventfoto.common.exception.UploadInProgressException;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.photo.dto.ConfirmUploadRequestDTO;
import com.tuapp.eventfoto.photo.dto.PhotoResponseDTO;
import com.tuapp.eventfoto.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Fase 9.0 - Bloque C: POST /confirm tiene que ser seguro de reintentar. El frontend
 * puede llamarlo más de una vez con la MISMA upload_key si la respuesta de un intento
 * anterior se perdió por la red (el PUT a R2 ya había terminado). Sin la reclamación
 * atómica de PhotoUploadClaimService, un segundo confirmUpload con la misma key crea una
 * SEGUNDA fila Photo y descuenta cupo dos veces (JPEG), o directamente falla con 500
 * porque el objeto HEIC original ya fue borrado por la primera llamada.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConfirmUploadIdempotencyTest {

    private static final byte[] JPEG_HEADER = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    // Firma HEIC real: caja ISOBMFF "ftyp" en bytes 4-7 + brand "heic" en bytes 8-11.
    private static final byte[] HEIC_HEADER = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'};
    private static final byte[] CONVERTED_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};

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

        Organizer organizer = organizerRepository.save(Organizer.builder().email("idempotency@test.com").build());

        event = eventRepository.save(Event.builder()
                .organizer(organizer)
                .name("Evento de Prueba")
                .slug("evento-demo-k7m2xq9p")
                .eventDate(LocalDate.now(UploadWindow.ZONE))
                .isActive(true)
                .origin(EventOrigin.PAID)
                .build());

        when(storageService.generatePublicUrl(anyString())).thenAnswer(inv -> "https://r2.test/" + inv.getArgument(0));
    }

    @Test
    @DisplayName("Reintento secuencial (JPEG): la segunda llamada con la misma upload_key devuelve la MISMA foto, sin crear otra ni descontar cupo dos veces")
    void sequentialRetryWithSameKeyDoesNotDuplicate() {
        String key = key(".jpg");
        when(storageService.streamObject(key)).thenAnswer(inv -> new ByteArrayInputStream(JPEG_HEADER));
        ConfirmUploadRequestDTO request = new ConfirmUploadRequestDTO(key, "Invitado", null, "guest-token-retry-jpeg");

        PhotoResponseDTO first = photoService.confirmUpload("evento-demo-k7m2xq9p", request);
        PhotoResponseDTO second = photoService.confirmUpload("evento-demo-k7m2xq9p", request);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(photoRepository.count()).isEqualTo(1);
        assertThat(guestQuotaRepository.findByEventIdAndGuestToken(event.getId(), "guest-token-retry-jpeg").orElseThrow().getPhotosUploaded())
                .isEqualTo(1);
        // La segunda llamada nunca vuelve a leer el objeto de storage.
        verify(storageService, times(1)).streamObject(key);
    }

    @Test
    @DisplayName("Reintento secuencial (HEIC): la segunda llamada NO intenta releer el objeto original ya borrado, y devuelve la foto con la key .jpg convertida")
    void sequentialRetryWithHeicConversionDoesNotDuplicate() {
        String heicKey = key(".heic");
        when(storageService.streamObject(heicKey)).thenAnswer(inv -> new ByteArrayInputStream(HEIC_HEADER));
        when(storageService.convertHeicToJpeg(any())).thenReturn(CONVERTED_JPEG);
        ConfirmUploadRequestDTO request = new ConfirmUploadRequestDTO(heicKey, "Invitado", null, "guest-token-retry-heic");

        PhotoResponseDTO first = photoService.confirmUpload("evento-demo-k7m2xq9p", request);
        assertThat(first.storageKey()).endsWith(".jpg").isNotEqualTo(heicKey);
        verify(storageService, times(1)).deleteFile(heicKey); // el original HEIC se borró

        // Reintento: si volviera a leer heicKey, streamObject() sigue devolviendo bytes HEIC
        // (el mock no lo prohíbe), pero con la reclamación NUNCA debería intentarlo.
        PhotoResponseDTO second = photoService.confirmUpload("evento-demo-k7m2xq9p", request);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.storageKey()).isEqualTo(first.storageKey());
        assertThat(photoRepository.count()).isEqualTo(1);
        assertThat(guestQuotaRepository.findByEventIdAndGuestToken(event.getId(), "guest-token-retry-heic").orElseThrow().getPhotosUploaded())
                .isEqualTo(1);
        verify(storageService, times(1)).streamObject(heicKey);
        verify(storageService, times(1)).convertHeicToJpeg(any());
    }

    @Test
    @DisplayName("Dos hilos reales confirmando la MISMA upload_key en paralelo: una sola foto, un solo descuento de cupo, y el perdedor falla RÁPIDO (no bloqueado hasta que el ganador termine)")
    void concurrentConfirmsWithSameKeyNeverDuplicateAndLoserFailsFast() throws Exception {
        String key = key(".jpg");
        Duration slowStorage = Duration.ofMillis(400);
        when(storageService.streamObject(key)).thenAnswer(inv -> {
            Thread.sleep(slowStorage.toMillis());
            return new ByteArrayInputStream(JPEG_HEADER);
        });
        ConfirmUploadRequestDTO request = new ConfirmUploadRequestDTO(key, "Invitado", null, "guest-token-race");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Callable<Attempt> task = () -> {
            ready.countDown();
            go.await();
            long start = System.nanoTime();
            try {
                PhotoResponseDTO photo = photoService.confirmUpload("evento-demo-k7m2xq9p", request);
                return new Attempt(photo, null, elapsedMs(start));
            } catch (RuntimeException e) {
                return new Attempt(null, e, elapsedMs(start));
            }
        };

        Future<Attempt> f1 = pool.submit(task);
        Future<Attempt> f2 = pool.submit(task);
        assertThat(ready.await(2, TimeUnit.SECONDS)).as("ambos hilos deben llegar a la largada").isTrue();
        go.countDown();

        Attempt r1 = f1.get(5, TimeUnit.SECONDS);
        Attempt r2 = f2.get(5, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        List<Attempt> results = List.of(r1, r2);
        List<Attempt> winners = results.stream().filter(Attempt::succeeded).toList();
        List<Attempt> losers = results.stream().filter(a -> !a.succeeded()).toList();

        assertThat(winners).as("exactamente un hilo debe confirmar la foto").hasSize(1);
        assertThat(losers).as("exactamente un hilo debe perder la carrera").hasSize(1);

        // Estado final: una sola foto, un solo descuento de cupo -- sin importar el camino.
        assertThat(photoRepository.count()).isEqualTo(1);
        assertThat(guestQuotaRepository.findByEventIdAndGuestToken(event.getId(), "guest-token-race").orElseThrow().getPhotosUploaded())
                .isEqualTo(1);

        Attempt winner = winners.get(0);
        Attempt loser = losers.get(0);

        // El punto central del ajuste de diseño: el perdedor falla por el MUTEX barato
        // (photo_upload_claims en su propia transacción corta), no porque haya esperado
        // bloqueado en el lock de fila hasta que el ganador terminara la "conversión" de
        // 400ms. Si la reclamación viviera en la misma transacción que el resto del
        // procesamiento, este assert de latencia fallaría (el perdedor tardaría >= 400ms).
        assertThat(loser.error()).as("el perdedor debe recibir el 503 transitorio, no un error genérico").isInstanceOf(UploadInProgressException.class);
        assertThat(loser.elapsedMs())
                .as("el perdedor debe fallar RÁPIDO por el mutex, sin bloquearse hasta que el ganador termine la conversión")
                .isLessThan(slowStorage.toMillis() / 2);

        // El ganador sí paga el costo real de la lectura/conversión de storage.
        assertThat(winner.elapsedMs()).isGreaterThanOrEqualTo(slowStorage.toMillis());
        assertThat(winner.photo().id()).isEqualTo(photoRepository.findAll().get(0).getId());
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private record Attempt(PhotoResponseDTO photo, RuntimeException error, long elapsedMs) {
        boolean succeeded() {
            return photo != null;
        }
    }

    /** Clave con el formato events/{eventId}/{uuid}.ext (el único que acepta /confirm). */
    private String key(String extension) {
        return "events/" + event.getId() + "/" + java.util.UUID.randomUUID() + extension;
    }
}
