package com.tuapp.eventfoto.photo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuapp.eventfoto.common.config.RateLimiterService;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.event.UploadWindow;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.photo.dto.ConfirmUploadRequestDTO;
import com.tuapp.eventfoto.storage.ImageContent;
import com.tuapp.eventfoto.storage.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.InputStreamSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Las subidas no cargan la foto entera en memoria: /confirm lee solo el encabezado de un JPEG, y en HEIC el contenido
 * llega por stream al conversor sin perder ni duplicar bytes (el encabezado ya leído va adelante del resto).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UploadStreamingTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EventRepository eventRepository;
    @Autowired private PhotoRepository photoRepository;
    @Autowired private GuestQuotaRepository guestQuotaRepository;
    @Autowired private PhotoUploadClaimRepository claimRepository;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private RateLimiterService rateLimiterService;

    @MockBean private StorageService storageService;

    private Event event;

    @BeforeEach
    void setUp() {
        rateLimiterService.resetRateLimits();
        cleanUp();

        Organizer organizer = organizerRepository.save(Organizer.builder().email("stream@test.com").build());
        event = eventRepository.save(Event.builder()
                .organizer(organizer)
                .name("Evento de Prueba")
                .slug("evento-stream-k7m2xq9p")
                .eventDate(LocalDate.now(UploadWindow.ZONE))
                .isActive(true)
                .origin(EventOrigin.PAID)
                .build());

        when(storageService.generatePublicUrl(anyString())).thenAnswer(inv -> "https://fake-r2/" + inv.getArgument(0));
    }

    /** La H2 es compartida entre clases de test: sin esto quedan fotos, cupos y reclamaciones que rompen a otros. */
    @AfterEach
    void cleanUp() {
        claimRepository.deleteAll();
        photoRepository.deleteAll();
        guestQuotaRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
    }

    @Test
    @DisplayName("/confirm de un JPEG de 1 MB: del objeto en storage se leen solo los bytes del encabezado")
    void confirmJpegReadsOnlyTheHeader() throws Exception {
        byte[] jpeg = withHeader(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}, 1_000_000);
        String key = "events/" + event.getId() + "/" + UUID.randomUUID() + ".jpg";
        AtomicLong bytesRead = new AtomicLong();
        when(storageService.streamObject(key)).thenAnswer(inv -> counting(new ByteArrayInputStream(jpeg), bytesRead));

        confirm(key, "token-stream-jpeg").andExpect(status().isCreated());

        assertThat(bytesRead.get()).as("bytes leídos del objeto").isLessThanOrEqualTo(ImageContent.HEADER_BYTES);
    }

    @Test
    @DisplayName("/confirm de un HEIC: el conversor recibe el objeto byte a byte, con el encabezado una sola vez")
    void confirmHeicStreamsIdenticalBytesToConverter() throws Exception {
        byte[] heic = withHeader(heicHeader(), 300_000);
        String key = "events/" + event.getId() + "/" + UUID.randomUUID() + ".heic";
        when(storageService.streamObject(key)).thenAnswer(inv -> new ByteArrayInputStream(heic));
        AtomicReference<byte[]> received = captureConverterInput();

        confirm(key, "token-stream-heic").andExpect(status().isCreated());

        assertIdentical(received.get(), heic);
    }

    @Test
    @DisplayName("upload-direct de un HEIC: el conversor recibe el archivo byte a byte")
    void directHeicStreamsIdenticalBytesToConverter() throws Exception {
        byte[] heic = withHeader(heicHeader(), 300_000);
        AtomicReference<byte[]> received = captureConverterInput();

        direct(new MockMultipartFile("file", "IMG.heic", "image/heic", heic), "token-direct-heic")
                .andExpect(status().isCreated());

        assertIdentical(received.get(), heic);
        verify(storageService).uploadFile(anyString(), any(), eq("image/jpeg"));
    }

    @Test
    @DisplayName("upload-direct de un JPEG: se sube por stream con su tamaño, y la fuente se puede reabrir (reintento del SDK)")
    void directJpegUploadsReopenableStream() throws Exception {
        byte[] jpeg = withHeader(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}, 300_000);
        List<byte[]> attempts = new ArrayList<>();
        AtomicLong declaredSize = new AtomicLong();
        doAnswer(inv -> {
            InputStreamSource source = inv.getArgument(1);
            declaredSize.set(inv.getArgument(2));
            for (int i = 0; i < 2; i++) { // dos intentos, como un PUT que el SDK reintenta
                try (InputStream in = source.getInputStream()) {
                    attempts.add(in.readAllBytes());
                }
            }
            return null;
        }).when(storageService).uploadStream(anyString(), any(), anyLong(), anyString());

        direct(new MockMultipartFile("file", "foto.jpg", "image/jpeg", jpeg), "token-direct-jpeg")
                .andExpect(status().isCreated());

        assertThat(declaredSize.get()).isEqualTo(jpeg.length);
        assertThat(attempts).hasSize(2).allSatisfy(bytes -> assertIdentical(bytes, jpeg));
    }

    /** Sin volcar los arrays enteros en el mensaje: la posición del primer byte distinto alcanza. */
    private static void assertIdentical(byte[] actual, byte[] expected) {
        assertThat(Arrays.mismatch(actual, expected)).as("primer byte distinto (-1 = idénticos)").isEqualTo(-1);
    }

    /** El conversor fake guarda lo que le llegó por el stream y devuelve un JPEG temporal. */
    private AtomicReference<byte[]> captureConverterInput() throws IOException {
        AtomicReference<byte[]> received = new AtomicReference<>();
        when(storageService.convertHeicToJpeg(any(InputStream.class))).thenAnswer(inv -> {
            received.set(inv.getArgument(0, InputStream.class).readAllBytes());
            return Files.write(Files.createTempFile("test-jpeg", ".jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
        });
        return received;
    }

    private org.springframework.test.web.servlet.ResultActions confirm(String key, String guestToken) throws Exception {
        return mockMvc.perform(post("/api/v1/events/" + event.getSlug() + "/photos/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ConfirmUploadRequestDTO(key, "Ana", null, guestToken))));
    }

    private org.springframework.test.web.servlet.ResultActions direct(MockMultipartFile file, String guestToken) throws Exception {
        return mockMvc.perform(multipart("/api/v1/events/" + event.getSlug() + "/photos/upload-direct")
                .file(file).param("uploaderName", "Ana").param("guestToken", guestToken));
    }

    /** {@code header} seguido de bytes al azar, hasta {@code size}. */
    private static byte[] withHeader(byte[] header, int size) {
        byte[] bytes = new byte[size];
        new Random(7).nextBytes(bytes);
        System.arraycopy(header, 0, bytes, 0, header.length);
        return bytes;
    }

    /** Caja ISOBMFF "ftyp" con major brand "heic" (lo que mira isHeicSignature). */
    private static byte[] heicHeader() {
        byte[] h = new byte[12];
        h[3] = 0x18;
        System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, h, 4, 4);
        System.arraycopy("heic".getBytes(StandardCharsets.US_ASCII), 0, h, 8, 4);
        return h;
    }

    private static InputStream counting(InputStream in, AtomicLong counter) {
        return new FilterInputStream(in) {
            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b != -1) {
                    counter.incrementAndGet();
                }
                return b;
            }

            @Override
            public int read(byte[] buf, int off, int len) throws IOException {
                int n = super.read(buf, off, len);
                if (n > 0) {
                    counter.addAndGet(n);
                }
                return n;
            }

            @Override
            public long skip(long n) throws IOException {
                long skipped = super.skip(n);
                counter.addAndGet(skipped);
                return skipped;
            }
        };
    }
}
