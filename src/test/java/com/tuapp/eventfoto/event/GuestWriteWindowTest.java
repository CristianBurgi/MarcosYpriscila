package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.comment.CommentRepository;
import com.tuapp.eventfoto.message.MessageRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.photo.GuestQuotaRepository;
import com.tuapp.eventfoto.photo.Photo;
import com.tuapp.eventfoto.photo.PhotoRepository;
import com.tuapp.eventfoto.photo.PhotoUploadClaimRepository;
import com.tuapp.eventfoto.storage.StorageService;
import com.tuapp.eventfoto.testsupport.MutableClock;
import com.tuapp.eventfoto.testsupport.TestEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 9.6: la ventana de escritura aplica a los cinco caminos de escritura de invitados (upload-url, confirm,
 * upload-direct, mensajes, comentarios), por HTTP, con el reloj fijo en UTC. Evento el 15/11.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MutableClock.Config.class)
class GuestWriteWindowTest {

    private static final String SLUG = "ventana-de-subida-k7m2xq9p";
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1, 1};
    private static final AtomicInteger TOKENS = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private MutableClock clock;
    @Autowired private EventRepository eventRepository;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private PhotoRepository photoRepository;
    @Autowired private CommentRepository commentRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private GuestQuotaRepository guestQuotaRepository;
    @Autowired private PhotoUploadClaimRepository photoUploadClaimRepository;
    @MockBean private StorageService storageService;

    private Event event;
    private Photo photo;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        Organizer organizer = organizerRepository.save(Organizer.builder().email("ventana@test.com").build());
        event = eventRepository.save(TestEvents.base(organizer, SLUG).eventDate(LocalDate.of(2026, 11, 15)).maxPhotosPerGuest(null).build());
        photo = photoRepository.save(Photo.builder().event(event).storageKey("events/" + event.getId() + "/foto.jpg").build());

        when(storageService.generateUploadUrl(anyString(), anyString())).thenReturn("https://r2.test/put");
        when(storageService.generatePublicUrl(anyString())).thenAnswer(inv -> "https://r2.test/" + inv.getArgument(0));
        when(storageService.objectSize(anyString())).thenReturn(OptionalLong.of(JPEG.length));
        when(storageService.streamObject(anyString())).thenAnswer(inv -> new ByteArrayInputStream(JPEG));
    }

    /** La H2 en memoria es la misma para todos los contextos de la suite: no dejar cupos ni claims a otros tests. */
    @AfterEach
    void cleanDatabase() {
        commentRepository.deleteAll();
        messageRepository.deleteAll();
        guestQuotaRepository.deleteAll();
        photoUploadClaimRepository.deleteAll();
        photoRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
    }

    private static String token() {
        return "guest-window-" + TOKENS.incrementAndGet();
    }

    private ResultActions uploadUrl() throws Exception {
        return mockMvc.perform(post("/api/v1/events/" + SLUG + "/photos/upload-url").contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentType\":\"image/jpeg\",\"filename\":\"foto.jpg\",\"guestToken\":\"" + token() + "\"}"));
    }

    private ResultActions confirm() throws Exception {
        String key = "events/" + event.getId() + "/" + UUID.randomUUID() + ".jpg";
        return mockMvc.perform(post("/api/v1/events/" + SLUG + "/photos/confirm").contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"" + key + "\",\"uploaderName\":\"Caro\",\"guestToken\":\"" + token() + "\"}"));
    }

    private ResultActions uploadDirect() throws Exception {
        return mockMvc.perform(multipart("/api/v1/events/" + SLUG + "/photos/upload-direct")
                .file(new MockMultipartFile("file", "foto.jpg", "image/jpeg", JPEG))
                .param("uploaderName", "Caro").param("guestToken", token()));
    }

    private ResultActions message() throws Exception {
        return mockMvc.perform(post("/api/v1/events/" + SLUG + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"authorName\":\"Caro\",\"text\":\"Felicidades\",\"guestToken\":\"" + token() + "\"}"));
    }

    private ResultActions comment() throws Exception {
        return mockMvc.perform(post("/api/v1/events/" + SLUG + "/photos/" + photo.getId() + "/comments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"authorName\":\"Caro\",\"text\":\"Hermosa\",\"guestToken\":\"" + token() + "\"}"));
    }

    private void expectAllRejected(String code) throws Exception {
        uploadUrl().andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(code));
        confirm().andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(code));
        uploadDirect().andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(code));
        message().andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(code));
        comment().andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(code));
    }

    private void expectAllAccepted() throws Exception {
        uploadUrl().andExpect(status().isOk());
        confirm().andExpect(status().isCreated());
        uploadDirect().andExpect(status().isCreated());
        message().andExpect(status().isCreated());
        comment().andExpect(status().isCreated());
    }

    @Test
    @DisplayName("13/11 23:59 cerrada (UPLOAD_NOT_OPEN), 14/11 00:00 y 16/11 23:59 abierta, 17/11 00:00 cerrada (UPLOAD_CLOSED), en los cinco caminos")
    void windowAppliesToEveryGuestWrite() throws Exception {
        clock.setArgentina(LocalDateTime.parse("2026-11-13T23:59:00"));
        expectAllRejected("UPLOAD_NOT_OPEN");

        clock.setArgentina(LocalDateTime.parse("2026-11-14T00:00:00"));
        expectAllAccepted();

        clock.setArgentina(LocalDateTime.parse("2026-11-16T23:59:00"));
        expectAllAccepted();

        clock.setArgentina(LocalDateTime.parse("2026-11-17T00:16:00"));
        expectAllRejected("UPLOAD_CLOSED");
    }

    @Test
    @DisplayName("17/11 00:00 a 00:15: upload-url, mensajes y comentarios ya cerraron; /confirm y upload-direct todavía terminan la subida")
    void graceOnlyForFinishingAnUpload() throws Exception {
        clock.setArgentina(LocalDateTime.parse("2026-11-17T00:00:00"));
        uploadUrl().andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("UPLOAD_CLOSED"))
                .andExpect(jsonPath("$.message").value("La subida de fotos ya cerró."));
        message().andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("El libro de visitas ya cerró."));
        comment().andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Los comentarios ya cerraron."));

        clock.setArgentina(LocalDateTime.parse("2026-11-17T00:15:00"));
        confirm().andExpect(status().isCreated());
        uploadDirect().andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Sin fecha (wizard sin completar) y con isActive=false: todo cerrado, con su código")
    void noDateAndManualClose() throws Exception {
        clock.setArgentina(LocalDateTime.parse("2026-11-15T12:00:00"));
        event.setEventDate(null);
        eventRepository.save(event);
        expectAllRejected("EVENT_NOT_READY");

        event.setEventDate(LocalDate.of(2026, 11, 15));
        event.setActive(false);
        eventRepository.save(event);
        expectAllRejected("EVENT_CLOSED");
    }

    @Test
    @DisplayName("GET /api/v1/events/{slug} informa el estado y el texto para que la página bloquee antes de elegir una foto")
    void statusIsPublished() throws Exception {
        clock.setArgentina(LocalDateTime.parse("2026-11-10T12:00:00"));
        mockMvc.perform(get("/api/v1/events/" + SLUG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadStatus").value("NOT_OPEN"))
                .andExpect(jsonPath("$.uploadMessage").value("La subida de fotos todavía no está habilitada. Se abre el sábado 14 de noviembre a las 00:00."))
                .andExpect(jsonPath("$.guestbookMessage").value("El libro de visitas todavía no está habilitado. Se abre el sábado 14 de noviembre a las 00:00."))
                .andExpect(jsonPath("$.uploadDeadline").doesNotExist());

        clock.setArgentina(LocalDateTime.parse("2026-11-15T12:00:00"));
        mockMvc.perform(get("/api/v1/events/" + SLUG))
                .andExpect(jsonPath("$.uploadStatus").value("OPEN"))
                .andExpect(jsonPath("$.uploadMessage").doesNotExist());
    }
}
