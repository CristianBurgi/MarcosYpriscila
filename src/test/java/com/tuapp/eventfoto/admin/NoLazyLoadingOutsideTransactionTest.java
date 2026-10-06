package com.tuapp.eventfoto.admin;

import com.tuapp.eventfoto.comment.Comment;
import com.tuapp.eventfoto.comment.CommentRepository;
import com.tuapp.eventfoto.common.config.JwtAuthenticationFilter;
import com.tuapp.eventfoto.common.config.JwtTokenProvider;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.message.Message;
import com.tuapp.eventfoto.message.MessageRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.photo.Photo;
import com.tuapp.eventfoto.photo.PhotoRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Con spring.jpa.open-in-view=false ninguna vista ni controller puede depender de lazy
 * loading fuera de transacción. Los demás tests de integración son @Transactional (la
 * transacción del test abarca toda la request y taparía un LazyInitializationException);
 * este NO lo es: los datos están commiteados y cada request corre como en producción.
 * Cubre el dashboard (CommentResponseDTO -> photo.storageKey), las listas del panel y de
 * la API pública, y el PDF del libro de visitas.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NoLazyLoadingOutsideTransactionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private PhotoRepository photoRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private CommentRepository commentRepository;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    private Cookie cookie;
    private Event event;

    @BeforeEach
    void setUp() {
        cleanUp();
        Organizer organizer = organizerRepository.save(Organizer.builder().email("lazy@test.com").build());
        event = eventRepository.save(Event.builder()
                .organizer(organizer).name("Evento Lazy").slug("evento-lazy-k7m2xq9p")
                .eventDate(LocalDate.now().plusDays(1)).uploadDeadline(Instant.now().plusSeconds(864000))
                .isActive(true).origin(EventOrigin.PAID).wizardCompletedAt(Instant.now()).build());
        Photo photo = photoRepository.save(Photo.builder().event(event).storageKey("photos/lazy/1.jpg").uploaderName("Ana").build());
        commentRepository.save(Comment.builder().photo(photo).authorName("Beto").text("Linda foto").build());
        messageRepository.save(Message.builder().event(event).authorName("Carla").text("Hola a todos").build());
        cookie = new Cookie(JwtAuthenticationFilter.ORGANIZER_COOKIE_NAME,
                jwtTokenProvider.generateOrganizerToken(organizer.getId(), organizer.getEmail(), organizer.getTokenVersion()));
    }

    @AfterEach
    void cleanUp() {
        commentRepository.deleteAll();
        messageRepository.deleteAll();
        photoRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
    }

    @Test
    @DisplayName("Dashboard del evento, lista de eventos y listas del panel funcionan sin sesión de Hibernate abierta")
    void panelPagesDoNotNeedLazyLoading() throws Exception {
        mockMvc.perform(get("/admin/eventos/" + event.getSlug()).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(model().attribute("photos", hasSize(1)))
                .andExpect(model().attribute("messages", hasSize(1)))
                .andExpect(model().attribute("photoComments", hasSize(1)))
                .andExpect(content().string(containsString("Linda foto")));

        mockMvc.perform(get("/admin/eventos").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Evento Lazy")));

        mockMvc.perform(get("/api/v1/admin/events/" + event.getSlug() + "/photos").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].eventId").value(event.getId().toString()))
                .andExpect(jsonPath("$.content[0].comments[0].text").value("Linda foto"));
    }

    @Test
    @DisplayName("API pública (fotos con comentarios, mensajes) y PDF del libro de visitas funcionan sin sesión de Hibernate abierta")
    void publicApiAndGuestbookPdfDoNotNeedLazyLoading() throws Exception {
        mockMvc.perform(get("/api/v1/events/" + event.getSlug() + "/photos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].comments[0].text").value("Linda foto"));
        mockMvc.perform(get("/api/v1/events/" + event.getSlug() + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].text").value("Hola a todos"));
        mockMvc.perform(get("/api/v1/admin/events/" + event.getSlug() + "/libro-de-visitas.pdf").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"));
    }

    @Test
    @DisplayName("Página, listas y borrado del moderador funcionan sin sesión de Hibernate abierta")
    void moderatorEndpointsDoNotNeedLazyLoading() throws Exception {
        String token = eventRepository.findById(event.getId()).orElseThrow().getModeratorToken();
        var photoId = photoRepository.findAll().get(0).getId();
        var messageId = messageRepository.findAll().get(0).getId();

        mockMvc.perform(get("/moderar/" + token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Evento Lazy")));
        mockMvc.perform(get("/api/v1/moderate/" + token + "/photos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(photoId.toString()))
                .andExpect(jsonPath("$.items[0].uploaderName").value("Ana"));
        mockMvc.perform(get("/api/v1/moderate/" + token + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].text").value("Hola a todos"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/moderate/" + token + "/messages/" + messageId))
                .andExpect(status().isNoContent());
        // Borrar una foto con comentarios: la cascada y el broadcast corren dentro de la transacción del servicio.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/moderate/" + token + "/photos/" + photoId))
                .andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThat(photoRepository.count()).isZero();
        org.assertj.core.api.Assertions.assertThat(commentRepository.count()).isZero();
    }
}
