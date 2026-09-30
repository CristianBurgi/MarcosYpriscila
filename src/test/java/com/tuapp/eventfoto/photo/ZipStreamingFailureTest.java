package com.tuapp.eventfoto.photo;

import com.tuapp.eventfoto.comment.CommentRepository;
import com.tuapp.eventfoto.common.config.RateLimiterService;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventService;
import com.tuapp.eventfoto.message.GuestbookPdfService;
import com.tuapp.eventfoto.realtime.SseBroadcaster;
import com.tuapp.eventfoto.storage.StorageService;
import io.sentry.Sentry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Fase 9.0: en el streaming del ZIP hay dos puntas que pueden fallar y NO son lo mismo.
 * - Leer de R2 falla -> error real: tiene que llegar a Sentry (y el resto del álbum se
 *   sigue descargando).
 * - Escribir al cliente falla (canceló la descarga) -> desconexión normal: sin Sentry.
 */
class ZipStreamingFailureTest {

    private static final byte[] PHOTO_OK_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4, 5};

    private PhotoRepository photoRepository;
    private EventService eventService;
    private StorageService storageService;
    private GuestbookPdfService guestbookPdfService;
    private PhotoServiceImpl photoService;
    private Event event;
    private Photo brokenInR2;
    private Photo healthy;

    @BeforeEach
    void setUp() {
        photoRepository = mock(PhotoRepository.class);
        eventService = mock(EventService.class);
        storageService = mock(StorageService.class);
        guestbookPdfService = mock(GuestbookPdfService.class);
        photoService = new PhotoServiceImpl(photoRepository, mock(CommentRepository.class), eventService, storageService,
                mock(SseBroadcaster.class), mock(RateLimiterService.class), mock(GuestQuotaService.class),
                mock(PhotoPersistenceService.class), guestbookPdfService, mock(PhotoUploadClaimService.class));

        event = Event.builder().id(UUID.randomUUID()).slug("evento-demo-k7m2xq9p").build();
        brokenInR2 = Photo.builder().id(UUID.randomUUID()).event(event).storageKey("photos/evento-demo-k7m2xq9p/rota.jpg").uploaderName("Ana").build();
        healthy = Photo.builder().id(UUID.randomUUID()).event(event).storageKey("photos/evento-demo-k7m2xq9p/sana.jpg").uploaderName("Beto").build();

        when(eventService.getEventEntityBySlug("evento-demo-k7m2xq9p")).thenReturn(event);
        when(photoRepository.findByEventId(event.getId())).thenReturn(List.of(brokenInR2, healthy));
        when(storageService.streamObject(healthy.getStorageKey())).thenAnswer(inv -> new ByteArrayInputStream(PHOTO_OK_BYTES));
    }

    @Test
    @DisplayName("Si R2 corta la lectura de una foto a mitad del ZIP, se reporta a Sentry y el resto del álbum se descarga igual")
    void storageReadFailureIsReportedAndZipContinues() throws IOException {
        IOException r2Failure = new IOException("Connection reset");
        when(storageService.streamObject(brokenInR2.getStorageKey())).thenReturn(new FailingAfterFirstReadInputStream(r2Failure));
        ByteArrayOutputStream client = new ByteArrayOutputStream();

        try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
            photoService.streamPhotosZip("evento-demo-k7m2xq9p", null, client);

            sentry.verify(() -> Sentry.captureException(r2Failure));
            sentry.verifyNoMoreInteractions();
        }

        List<String> entries = new ArrayList<>();
        byte[] healthyContent = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(client.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.add(entry.getName());
                byte[] content = zip.readAllBytes();
                if (entry.getName().contains("Beto")) {
                    healthyContent = content;
                }
            }
        }
        assertThat(entries).hasSize(2);
        assertThat(healthyContent).isEqualTo(PHOTO_OK_BYTES);
    }

    @Test
    @DisplayName("Si R2 falla al abrir el objeto (excepción del SDK), también se reporta a Sentry")
    void storageOpenFailureIsReported() {
        RuntimeException sdkFailure = new IllegalStateException("Unable to execute HTTP request: Connection reset");
        when(storageService.streamObject(brokenInR2.getStorageKey())).thenThrow(sdkFailure);

        try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
            photoService.streamPhotosZip("evento-demo-k7m2xq9p", null, new ByteArrayOutputStream());

            sentry.verify(() -> Sentry.captureException(sdkFailure));
        }
    }

    @Test
    @DisplayName("Si el cliente corta la descarga (falla la ESCRITURA), no va a Sentry, no se relanza y se deja de leer de R2")
    void clientWriteFailureIsNotReported() {
        when(storageService.streamObject(brokenInR2.getStorageKey())).thenAnswer(inv -> new ByteArrayInputStream(PHOTO_OK_BYTES));
        OutputStream clientThatHungUp = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("Broken pipe");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("Broken pipe");
            }
        };

        try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
            assertThatCode(() -> photoService.streamPhotosZip("evento-demo-k7m2xq9p", null, clientThatHungUp))
                    .doesNotThrowAnyException();

            sentry.verifyNoInteractions();
        }
        // Abortó en la primera foto: nunca pidió la segunda a R2.
        verify(storageService, never()).streamObject(healthy.getStorageKey());
    }

    @Test
    @DisplayName("Si falla la generación del libro de visitas, el ZIP de fotos sale igual (sin el PDF) y el error va a Sentry")
    void guestbookFailureDoesNotBlockThePhotos() throws IOException {
        when(storageService.streamObject(brokenInR2.getStorageKey())).thenAnswer(inv -> new ByteArrayInputStream(PHOTO_OK_BYTES));
        IllegalStateException pdfFailure = new IllegalStateException("fuente corrupta");
        when(guestbookPdfService.generate("evento-demo-k7m2xq9p")).thenThrow(pdfFailure);
        ByteArrayOutputStream client = new ByteArrayOutputStream();

        try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
            assertThatCode(() -> photoService.streamPhotosZip("evento-demo-k7m2xq9p", null, client)).doesNotThrowAnyException();
            sentry.verify(() -> Sentry.captureException(pdfFailure));
        }

        List<String> entries = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(client.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) entries.add(entry.getName());
        }
        assertThat(entries).hasSize(2).doesNotContain(GuestbookPdfService.FILENAME);
    }

    /** Devuelve algunos bytes y después falla, como una conexión a R2 que se corta a mitad. */
    private static final class FailingAfterFirstReadInputStream extends InputStream {
        private final IOException failure;
        private boolean firstReadDone;

        FailingAfterFirstReadInputStream(IOException failure) {
            this.failure = failure;
        }

        @Override
        public int read() throws IOException {
            throw failure;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (!firstReadDone) {
                firstReadDone = true;
                b[off] = (byte) 0xFF;
                return 1;
            }
            throw failure;
        }
    }
}
