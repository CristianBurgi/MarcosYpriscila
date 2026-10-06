package com.tuapp.eventfoto.realtime;

import com.tuapp.eventfoto.comment.Comment;
import com.tuapp.eventfoto.comment.CommentRepository;
import com.tuapp.eventfoto.common.config.JwtTokenProvider;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.message.MessageRepository;
import com.tuapp.eventfoto.message.MessageService;
import com.tuapp.eventfoto.message.dto.CreateMessageRequestDTO;
import com.tuapp.eventfoto.message.dto.MessageResponseDTO;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.photo.GuestQuotaRepository;
import com.tuapp.eventfoto.photo.PhotoRepository;
import com.tuapp.eventfoto.photo.PhotoService;
import com.tuapp.eventfoto.photo.dto.PhotoResponseDTO;
import com.tuapp.eventfoto.storage.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 9.1 Bloque 4: dos eventos en vivo con una pantalla conectada cada uno. Todo lo que pasa
 * en A (foto subida y borrada, mensaje creado y borrado, comentario borrado) llega a la
 * pantalla de A y NUNCA a la de B, y al revés.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SseEventIsolationTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};

    @Autowired private MockMvc mockMvc;
    @Autowired private SseBroadcaster sseBroadcaster;
    @Autowired private PhotoService photoService;
    @Autowired private MessageService messageService;
    @Autowired private EventRepository eventRepository;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private PhotoRepository photoRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private CommentRepository commentRepository;
    @Autowired private GuestQuotaRepository guestQuotaRepository;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @MockBean private StorageService storageService;

    private Organizer organizerA;
    private Organizer organizerB;
    private Event eventA;
    private Event eventB;
    private SseEmitter screenA;
    private SseEmitter screenB;

    @BeforeEach
    void setUp() throws IOException {
        cleanUp();
        organizerA = organizerRepository.save(Organizer.builder().email("sse-a@test.com").build());
        organizerB = organizerRepository.save(Organizer.builder().email("sse-b@test.com").build());
        eventA = event(organizerA, "evento-sse-a-k7m2xq9p");
        eventB = event(organizerB, "evento-sse-b-x4h8wt2n");
        when(storageService.generatePublicUrl(any())).thenReturn("https://r2.test/foto.jpg");

        screenA = mock(SseEmitter.class);
        screenB = mock(SseEmitter.class);
        sseBroadcaster.register(eventA.getId(), screenA);
        sseBroadcaster.register(eventB.getId(), screenB);
        clearInvocations(screenA, screenB); // descarta el INIT
    }

    @AfterEach
    void cleanUp() {
        commentRepository.deleteAll();
        messageRepository.deleteAll();
        photoRepository.deleteAll();
        guestQuotaRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
    }

    private Event event(Organizer organizer, String slug) {
        return eventRepository.save(Event.builder()
                .organizer(organizer).name("Evento " + slug).slug(slug)
                .eventDate(LocalDate.now().plusDays(1)).uploadDeadline(Instant.now().plusSeconds(864000))
                .isActive(true).origin(EventOrigin.PAID).wizardCompletedAt(Instant.now()).build());
    }

    private String bearer(Organizer organizer) {
        return "Bearer " + jwtTokenProvider.generateOrganizerToken(organizer.getId(), organizer.getEmail(), organizer.getTokenVersion());
    }

    private List<String> received(SseEmitter emitter) throws IOException {
        ArgumentCaptor<SseEmitter.SseEventBuilder> captor = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, atLeast(0)).send(captor.capture());
        return captor.getAllValues().stream().map(SseTestSupport::render).toList();
    }

    @Test
    @DisplayName("Subida, borrado de foto, mensaje creado/borrado y comentario borrado en A llegan a la pantalla de A y NUNCA a la de B")
    void everythingInEventAReachesOnlyScreenA() throws Exception {
        // 1. Foto subida en A
        PhotoResponseDTO photo = photoService.uploadDirect(eventA.getSlug(),
                new MockMultipartFile("file", "a.jpg", "image/jpeg", JPEG), "Ana", null, "token-sse-aaaa");
        // 2. Mensaje creado en A
        MessageResponseDTO message = messageService.addMessage(eventA.getSlug(),
                new CreateMessageRequestDTO("Beto", "SOLO-PARA-A"), "127.0.0.1");
        // 3. Comentario (se crea directo: no hay evento "comentario creado") y su borrado
        Comment comment = commentRepository.save(Comment.builder()
                .photo(photoRepository.findById(photo.id()).orElseThrow()).authorName("Carla").text("Linda").build());
        mockMvc.perform(delete("/api/v1/admin/events/" + eventA.getSlug() + "/comments/" + comment.getId())
                .header("Authorization", bearer(organizerA))).andExpect(status().isNoContent());
        // 4. Borrado de mensaje y de foto
        mockMvc.perform(delete("/api/v1/admin/events/" + eventA.getSlug() + "/messages/" + message.id())
                .header("Authorization", bearer(organizerA))).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/admin/events/" + eventA.getSlug() + "/photos/" + photo.id())
                .header("Authorization", bearer(organizerA))).andExpect(status().isNoContent());

        List<String> toA = received(screenA);
        System.out.println("SSE-ISOLATION pantalla A recibió: " + toA.stream().map(s -> s.split("\n")[0]).toList());
        assertThat(toA).anyMatch(s -> s.contains("PHOTO_PUBLISHED"));
        assertThat(toA).anyMatch(s -> s.contains("MESSAGE_CREATED"));
        assertThat(toA).anyMatch(s -> s.contains("COMMENT_DELETED"));
        assertThat(toA).anyMatch(s -> s.contains("MESSAGE_DELETED"));
        assertThat(toA).anyMatch(s -> s.contains("PHOTO_DELETED"));

        List<String> toB = received(screenB);
        System.out.println("SSE-ISOLATION pantalla B recibió: " + toB);
        assertThat(toB).as("la pantalla de B no debe recibir NINGÚN evento de A").noneMatch(s ->
                s.contains("PHOTO_") || s.contains("MESSAGE_") || s.contains("COMMENT_")
                        || s.contains(photo.id().toString()) || s.contains("SOLO-PARA-A"));
    }

    @Test
    @DisplayName("Y al revés: un mensaje y una foto de B no llegan a la pantalla de A")
    void everythingInEventBReachesOnlyScreenB() throws Exception {
        photoService.uploadDirect(eventB.getSlug(), new MockMultipartFile("file", "b.jpg", "image/jpeg", JPEG), "Beto", null, "token-sse-bbbb");
        messageService.addMessage(eventB.getSlug(), new CreateMessageRequestDTO("Dani", "SOLO-PARA-B"), "127.0.0.1");

        assertThat(received(screenB)).anyMatch(s -> s.contains("PHOTO_PUBLISHED")).anyMatch(s -> s.contains("MESSAGE_CREATED"));
        assertThat(received(screenA)).noneMatch(s -> s.contains("PHOTO_") || s.contains("MESSAGE_") || s.contains("SOLO-PARA-B"));
    }
}
