package com.tuapp.eventfoto.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuapp.eventfoto.event.UploadWindow;
import com.tuapp.eventfoto.comment.dto.CreateCommentRequestDTO;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.photo.Photo;
import com.tuapp.eventfoto.photo.PhotoRepository;
import com.tuapp.eventfoto.comment.CommentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 9.1 Bloque 4: seguridad por defecto del lado invitado. Los prefijos /api/v1/photos/**,
 * /api/v1/messages/** y /api/v1/comments/** ya no son públicos (nadie los usa): sin sesión no se
 * pueden usar. La ruta nueva de comentarios, colgada del evento, sí funciona para un invitado sin
 * sesión.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicRoutesSecurityDefaultsTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private PhotoRepository photoRepository;
    @Autowired private CommentRepository commentRepository;
    @Autowired private RateLimiterService rateLimiterService;

    private Event event;
    private Photo photo;

    @BeforeEach
    void setUp() {
        cleanUp();
        rateLimiterService.resetRateLimits();
        Organizer organizer = organizerRepository.save(Organizer.builder().email("defaults@test.com").build());
        event = eventRepository.save(Event.builder()
                .organizer(organizer).name("Evento Defaults").slug("evento-defaults-k7m2xq9p")
                .eventDate(LocalDate.now(UploadWindow.ZONE))
                .isActive(true).origin(EventOrigin.PAID).build());
        photo = photoRepository.save(Photo.builder().event(event)
                .storageKey("events/" + event.getId() + "/" + UUID.randomUUID() + ".jpg").build());
    }

    @AfterEach
    void cleanUp() {
        commentRepository.deleteAll();
        photoRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
    }

    @Test
    @DisplayName("Sin sesión, la ruta vieja /api/v1/photos/{photoId}/comments (GET y POST) ya no se puede usar: 401")
    void oldCommentsRouteIsNotPublicAnymore() throws Exception {
        mockMvc.perform(get("/api/v1/photos/" + photo.getId() + "/comments")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/photos/" + photo.getId() + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCommentRequestDTO("Ana", "Hola"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Sin sesión, los prefijos /api/v1/messages/** y /api/v1/comments/** (sin controller) tampoco son públicos: 401")
    void unusedPublicPrefixesAreClosed() throws Exception {
        mockMvc.perform(get("/api/v1/messages/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/comments/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/photos/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("La ruta nueva /api/v1/events/{slug}/photos/{photoId}/comments funciona para un invitado sin sesión (POST y GET)")
    void newCommentsRouteWorksForAnonymousGuest() throws Exception {
        String base = "/api/v1/events/" + event.getSlug() + "/photos/" + photo.getId() + "/comments";

        mockMvc.perform(post(base)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCommentRequestDTO("Ana", "Qué linda foto", "token-defaults-aaaa"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text", is("Qué linda foto")));

        mockMvc.perform(get(base))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].authorName", is("Ana")));
    }
}
