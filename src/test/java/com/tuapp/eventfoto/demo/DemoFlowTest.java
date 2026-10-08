package com.tuapp.eventfoto.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuapp.eventfoto.common.config.RateLimiterService;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.event.UploadWindow;
import com.tuapp.eventfoto.message.MessageRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.realtime.DemoSseTestAccess;
import com.tuapp.eventfoto.realtime.SseBroadcaster;
import com.tuapp.eventfoto.testsupport.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 9.7-B: la demo de punta a punta contra la base y el storage local (uploads/), con reloj fijo.
 * Privacidad entre demos, topes (también concurrentes), filtro de contenido, validación de archivos, EXIF y limpieza.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MutableClock.Config.class)
class DemoFlowTest {

    private static final Instant T0 = Instant.parse("2026-10-08T15:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private MockMvc mockMvc;
    @Autowired private MutableClock clock;
    @Autowired private DemoService demoService;
    @Autowired private DemoSessionRepository sessions;
    @Autowired private DemoPhotoRepository photos;
    @Autowired private DemoMessageRepository messages;
    @Autowired private SseBroadcaster sseBroadcaster;
    @Autowired private RateLimiterService rateLimiterService;
    @Autowired private EventRepository eventRepository;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private MessageRepository messageRepository;

    @BeforeEach
    void setUp() throws IOException {
        clock.set(T0);
        rateLimiterService.resetRateLimits();
        cleanUp();
    }

    @AfterEach
    void cleanUp() throws IOException {
        photos.deleteAll();
        messages.deleteAll();
        sessions.deleteAll();
        messageRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
        deleteTree(Path.of("uploads", "demo"));
    }

    // --- Privacidad ---

    @Test
    @DisplayName("Privacidad: lo que se sube en la demo A no aparece en la pantalla, el álbum, el ticker ni el SSE de la demo B")
    void demosDoNotSeeEachOther() throws Exception {
        String a = createDemo();
        String b = createDemo();
        SseEmitter screenA = mock(SseEmitter.class);
        SseEmitter screenB = mock(SseEmitter.class);
        DemoSseTestAccess.registerDemoScreen(sseBroadcaster, a, screenA);
        DemoSseTestAccess.registerDemoScreen(sseBroadcaster, b, screenB);
        clearInvocations(screenA, screenB); // descarta el INIT

        String photoId = json(upload(a, jpeg()).andExpect(status().isCreated())).get("id").asText();
        message(a, "Mensaje privado de la demo A").andExpect(status().isCreated());

        // SSE: A recibe la foto y el mensaje; B no recibe nada.
        List<String> sentToA = sent(screenA);
        assertThat(sentToA).anySatisfy(w -> assertThat(w).contains("event:PHOTO_PUBLISHED").contains(photoId));
        assertThat(sentToA).anySatisfy(w -> assertThat(w).contains("event:MESSAGE_CREATED").contains("Mensaje privado de la demo A"));
        verify(screenB, org.mockito.Mockito.never()).send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));

        // API: álbum/pantalla y ticker de B solo tienen lo de ejemplo.
        String photosB = body(get("/api/v1/demo/" + b + "/photos"));
        String messagesB = body(get("/api/v1/demo/" + b + "/messages"));
        assertThat(photosB).doesNotContain(photoId).doesNotContain("demo/" + a);
        assertThat(messagesB).doesNotContain("Mensaje privado de la demo A");
        assertThat(json(photosB).get("content")).hasSize(DemoExamples.PHOTO_COUNT);

        // Y A los ve, primero lo suyo.
        JsonNode photosA = json(body(get("/api/v1/demo/" + a + "/photos"))).get("content");
        assertThat(photosA).hasSize(DemoExamples.PHOTO_COUNT + 1);
        assertThat(photosA.get(0).get("id").asText()).isEqualTo(photoId);
        assertThat(photosA.get(0).get("uploaderName").asText()).isEqualTo("vos");
        assertThat(json(body(get("/api/v1/demo/" + a + "/messages"))).get("content").get(0).get("text").asText())
                .isEqualTo("Mensaje privado de la demo A");
    }

    // --- Topes ---

    @Test
    @DisplayName("Tope: 6 fotos simultáneas en una demo -> 5 guardadas y 1 rechazada con el mensaje; la sexta por la API da 409")
    void sixthPhotoIsRejectedEvenConcurrently() throws Exception {
        DemoSession session = sessions.findById(createDemo()).orElseThrow();
        List<Object> results = concurrently(6, () -> demoService.uploadPhoto(session, file(jpeg())));

        assertThat(results).filteredOn(r -> r instanceof ResponseStatusException).singleElement()
                .satisfies(r -> assertThat(((ResponseStatusException) r).getReason()).isEqualTo(DemoService.PHOTO_LIMIT_MESSAGE));
        assertThat(photos.countBySid(session.getSid())).isEqualTo(5);
        // El perdedor no deja un objeto huérfano: quedan exactamente los 5 archivos de las fotos guardadas.
        assertThat(filesUnder(Path.of("uploads", "demo", session.getSid()))).isEqualTo(5);

        upload(session.getSid(), jpeg()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("En la demo podés subir hasta 5 fotos. ¡En tu evento no hay límite!"));
    }

    @Test
    @DisplayName("Tope: 6 mensajes simultáneos -> 5 guardados y 1 rechazado; el sexto por la API da 409")
    void sixthMessageIsRejectedEvenConcurrently() throws Exception {
        DemoSession session = sessions.findById(createDemo()).orElseThrow();
        List<Object> results = concurrently(6, () -> demoService.addMessage(session,
                new com.tuapp.eventfoto.message.dto.CreateMessageRequestDTO("Ana", "Felicidades", null)));

        assertThat(results).filteredOn(r -> r instanceof ResponseStatusException).singleElement()
                .satisfies(r -> assertThat(((ResponseStatusException) r).getReason()).isEqualTo(DemoService.MESSAGE_LIMIT_MESSAGE));
        assertThat(messages.countBySid(session.getSid())).isEqualTo(5);

        message(session.getSid(), "Uno más").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("En la demo podés escribir hasta 5 mensajes. ¡En tu evento no hay límite!"));
    }

    // --- Mismas validaciones que un evento real ---

    @Test
    @DisplayName("Un mensaje con una palabra filtrada se rechaza igual que en un evento real (mismo status y mensaje) y no se guarda")
    void filteredWordIsTreatedLikeARealEvent() throws Exception {
        String blocked = firstBlockedWord();
        String text = "Qué linda fiesta " + blocked;
        String sid = createDemo();
        String slug = realEvent();

        String real = mockMvc.perform(post("/api/v1/events/" + slug + "/messages").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("authorName", "Ana", "text", text))))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String demo = message(sid, text).andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(json(demo).get("message").asText()).isEqualTo(json(real).get("message").asText());
        assertThat(messages.countBySid(sid)).isZero();
    }

    @Test
    @DisplayName("Un archivo que no es imagen se rechaza igual que en un evento real (422) y no se guarda nada")
    void nonImageIsRejected() throws Exception {
        String sid = createDemo();
        byte[] fake = "esto no es una imagen".getBytes(StandardCharsets.UTF_8);
        upload(sid, fake).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("no corresponde a una imagen válida")));
        assertThat(photos.countBySid(sid)).isZero();
        assertThat(filesUnder(Path.of("uploads", "demo", sid))).isZero();
    }

    @Test
    @DisplayName("EXIF: la foto y el fondo de la demo se guardan sin metadatos (GPS)")
    void exifIsStripped() throws Exception {
        String sid = json(mockMvc.perform(multipart("/api/v1/demo/sessions")
                        .file(new MockMultipartFile("file", "fondo.jpg", "image/jpeg", jpegWithExif()))
                        .param("date", "2026-12-12").param("color", "#7b2d8e"))
                .andExpect(status().isCreated())).get("url").asText().substring("/demo/".length());
        upload(sid, jpegWithExif()).andExpect(status().isCreated());

        try (Stream<Path> files = Files.walk(Path.of("uploads", "demo", sid))) {
            List<Path> stored = files.filter(Files::isRegularFile).toList();
            assertThat(stored).hasSize(2); // la foto y el fondo
            for (Path path : stored) {
                assertThat(new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1)).as(path.toString())
                        .doesNotContain("Exif").doesNotContain("GPS-SECRETO");
            }
        }
    }

    // --- Sesión y limpieza ---

    @Test
    @DisplayName("Un sid con otro formato o inexistente lleva a /demo?fin=1 y la API responde 404 \"Tu demo terminó.\"")
    void unknownSidEndsTheDemo() throws Exception {
        for (String sid : new String[]{"corto", DemoService.newSid(), "a".repeat(42) + "'"}) {
            mockMvc.perform(get("/demo/" + sid + "/pantalla")).andExpect(status().isFound())
                    .andExpect(header().string("Location", "/demo?fin=1"));
            mockMvc.perform(get("/api/v1/demo/" + sid)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Tu demo terminó."));
        }
        assertThat(body(get("/demo?fin=1"))).contains("Tu demo terminó. ¿Probás de nuevo?");
    }

    @Test
    @DisplayName("Limpieza: a los 29 minutos sigue todo; a los 31 se borra la demo entera, sin tocar events/ ni otra demo")
    void cleanupAt29And31Minutes() throws Exception {
        String old = json(mockMvc.perform(multipart("/api/v1/demo/sessions")
                        .file(new MockMultipartFile("file", "fondo.jpg", "image/jpeg", jpeg()))
                        .param("date", "2026-12-12").param("color", "#7b2d8e"))
                .andExpect(status().isCreated())).get("url").asText().substring("/demo/".length());
        upload(old, jpeg()).andExpect(status().isCreated());
        message(old, "Hola").andExpect(status().isCreated());
        clock.set(T0.plus(Duration.ofMinutes(20)));
        String recent = createDemo();
        upload(recent, jpeg()).andExpect(status().isCreated());
        Path eventObject = Path.of("uploads", "events", java.util.UUID.randomUUID().toString(), "vecino.jpg");
        Files.createDirectories(eventObject.getParent());
        Files.write(eventObject, jpeg());

        try {
            clock.set(T0.plus(Duration.ofMinutes(29)));
            assertThat(demoService.purgeExpired().sessions()).isZero();
            assertThat(filesUnder(Path.of("uploads", "demo", old))).isEqualTo(2);
            assertThat(sessions.existsById(old)).isTrue();
            mockMvc.perform(get("/demo/" + old)).andExpect(status().isOk());

            clock.set(T0.plus(Duration.ofMinutes(31)));
            DemoService.PurgeResult result = demoService.purgeExpired();
            assertThat(result.sessions()).isEqualTo(1);
            assertThat(result.objects()).isEqualTo(2);
            assertThat(Path.of("uploads", "demo", old)).doesNotExist();
            assertThat(sessions.existsById(old)).isFalse();
            assertThat(photos.countBySid(old)).isZero();
            assertThat(messages.countBySid(old)).isZero();
            mockMvc.perform(get("/demo/" + old)).andExpect(header().string("Location", "/demo?fin=1"));

            // La otra demo (11 minutos de vida) y el objeto de un evento real, intactos.
            assertThat(sessions.existsById(recent)).isTrue();
            assertThat(filesUnder(Path.of("uploads", "demo", recent))).isEqualTo(1);
            assertThat(eventObject).exists();
        } finally {
            deleteTree(eventObject.getParent());
        }
    }

    @Test
    @DisplayName("Vaciar demo: borra todas las demos (vigentes también) y los restos sueltos bajo demo/, sin tocar events/")
    void purgeAllEmptiesTheDemo() throws Exception {
        String a = createDemo();
        String b = createDemo();
        upload(a, jpeg()).andExpect(status().isCreated());
        upload(b, jpeg()).andExpect(status().isCreated());
        message(b, "Hola").andExpect(status().isCreated());
        Path leftover = Path.of("uploads", "demo", DemoService.newSid(), "huerfano.jpg"); // sin fila: un corte a mitad de algo
        Files.createDirectories(leftover.getParent());
        Files.write(leftover, jpeg());
        Path eventObject = Path.of("uploads", "events", java.util.UUID.randomUUID().toString(), "vecino.jpg");
        Files.createDirectories(eventObject.getParent());
        Files.write(eventObject, jpeg());
        try {
            DemoService.PurgeResult result = demoService.purgeAll();
            assertThat(result.sessions()).isEqualTo(2);
            assertThat(result.failed()).isZero();
            assertThat(sessions.count()).isZero();
            assertThat(photos.count()).isZero();
            assertThat(messages.count()).isZero();
            assertThat(filesUnder(Path.of("uploads", "demo"))).isZero();
            assertThat(eventObject).exists();
            assertThat(demoService.stats()).isEqualTo(new DemoService.Stats(0, 0));
        } finally {
            deleteTree(eventObject.getParent());
        }
    }

    @Test
    @DisplayName("Rate limit: dos demos desde la misma IP comparten el tope de 10 subidas por minuto")
    void uploadRateLimitIsPerIpAcrossDemos() throws Exception {
        String a = createDemo();
        String b = createDemo();
        for (int i = 0; i < 5; i++) {
            upload(a, jpeg()).andExpect(status().isCreated());
            upload(b, jpeg()).andExpect(status().isCreated());
        }
        // 11.ª subida del minuto desde la misma IP, en una tercera demo que todavía no subió nada.
        upload(createDemo(), jpeg()).andExpect(status().isTooManyRequests());
    }

    // --- Utilidades ---

    private String createDemo() throws Exception {
        String url = json(mockMvc.perform(multipart("/api/v1/demo/sessions").param("date", "2026-12-12").param("color", "#1f6f5c"))
                .andExpect(status().isCreated())).get("url").asText();
        return url.substring("/demo/".length());
    }

    private ResultActions upload(String sid, byte[] bytes) throws Exception {
        return mockMvc.perform(multipart("/api/v1/demo/" + sid + "/photos").file(file(bytes)));
    }

    private ResultActions message(String sid, String text) throws Exception {
        return mockMvc.perform(post("/api/v1/demo/" + sid + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("authorName", "Ana", "text", text))));
    }

    private String realEvent() {
        Organizer organizer = organizerRepository.save(Organizer.builder().email("demo-real@test.com").build());
        Event event = eventRepository.save(Event.builder().organizer(organizer).name("Real").slug("evento-real-k7m2xq9p")
                .eventDate(LocalDate.now(clock.withZone(UploadWindow.ZONE))).isActive(true).origin(EventOrigin.PAID).build());
        return event.getSlug();
    }

    private String body(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static JsonNode json(ResultActions result) throws Exception {
        return json(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode json(String body) throws IOException {
        return JSON.readTree(body);
    }

    private static List<String> sent(SseEmitter screen) throws IOException {
        ArgumentCaptor<SseEmitter.SseEventBuilder> captor = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(screen, atLeast(1)).send(captor.capture());
        return captor.getAllValues().stream().map(DemoSseTestAccess::render).toList();
    }

    /** Corre n tareas a la vez (salen juntas de una barrera); devuelve el resultado o la excepción de cada una. */
    private static List<Object> concurrently(int n, Callable<?> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            go.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "foto.jpg", "image/jpeg", bytes);
    }

    private static byte[] jpeg() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }

    private static byte[] jpegWithExif() throws IOException {
        byte[] jpeg = jpeg();
        byte[] payload = "Exif\0\0GPS-SECRETO-34.6037S-58.3816W".getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write((payload.length + 2) >> 8);
        out.write((payload.length + 2) & 0xFF);
        out.write(payload);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /** La primera palabra del mismo diccionario que usa el filtro: el test no la escribe a mano. */
    private static String firstBlockedWord() throws IOException {
        return new ClassPathResource("moderation/blocked-words-es.txt").getContentAsString(StandardCharsets.UTF_8).lines()
                .map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#")).findFirst().orElseThrow();
    }

    private static long filesUnder(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
