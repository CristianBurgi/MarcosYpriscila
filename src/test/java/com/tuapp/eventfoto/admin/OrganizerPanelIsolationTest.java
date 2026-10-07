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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Fase 9.1 Bloque 2: "Mis eventos" lista solo lo propio y cada ruta del panel resuelve el
 * evento desde la ruta contra el organizador logueado. Organizador A contra el evento de
 * B -> el mismo 404 que un slug inexistente, en cada endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OrganizerPanelIsolationTest {

    private static final String NOT_FOUND_MESSAGE = "Evento no encontrado";

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private PhotoRepository photoRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private CommentRepository commentRepository;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    private Organizer organizerA;
    private Organizer organizerB;
    private Event eventA;
    private Event eventB;
    private Photo photoA;
    private Photo photoB;
    private Message messageA;
    private Message messageB;
    private Comment commentA;
    private Comment commentB;

    @BeforeEach
    void setUp() {
        commentRepository.deleteAll();
        messageRepository.deleteAll();
        photoRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();

        organizerA = organizerRepository.save(Organizer.builder().email("a@test.com").build());
        organizerB = organizerRepository.save(Organizer.builder().email("b@test.com").build());
        eventA = event(organizerA, "Evento de A", "evento-de-a-k7m2xq9p");
        eventB = event(organizerB, "Evento de B", "evento-de-b-x4h8wt2n");

        photoA = photoRepository.save(Photo.builder().event(eventA).storageKey("photos/a/1.jpg").build());
        photoB = photoRepository.save(Photo.builder().event(eventB).storageKey("photos/b/1.jpg").build());
        messageA = messageRepository.save(Message.builder().event(eventA).authorName("Ana").text("Hola A").build());
        messageB = messageRepository.save(Message.builder().event(eventB).authorName("Beto").text("Hola B").build());
        commentA = commentRepository.save(Comment.builder().photo(photoA).authorName("Ana").text("Linda A").build());
        commentB = commentRepository.save(Comment.builder().photo(photoB).authorName("Beto").text("Linda B").build());
    }

    private Event event(Organizer organizer, String name, String slug) {
        return eventRepository.save(Event.builder()
                .organizer(organizer).name(name).slug(slug)
                .eventDate(LocalDate.now().plusDays(3))
                .isActive(true).origin(EventOrigin.PAID).wizardCompletedAt(Instant.now()).build());
    }

    private Cookie cookieOf(Organizer organizer) {
        return new Cookie(JwtAuthenticationFilter.ORGANIZER_COOKIE_NAME,
                jwtTokenProvider.generateOrganizerToken(organizer.getId(), organizer.getEmail(), organizer.getTokenVersion()));
    }

    // ---------- Mis eventos ----------

    @Test
    @DisplayName("Mis eventos lista solo los eventos del organizador logueado, con su cantidad de fotos")
    void myEventsListsOnlyOwnEvents() throws Exception {
        event(organizerA, "Segundo de A", "segundo-de-a-m3n5pq7r");

        mockMvc.perform(get("/admin/eventos").cookie(cookieOf(organizerA)))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/events"))
                .andExpect(model().attribute("events", hasSize(2)))
                .andExpect(content().string(containsString("Evento de A")))
                .andExpect(content().string(containsString("Segundo de A")))
                .andExpect(content().string(containsString("1 foto")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Evento de B"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(eventB.getSlug()))));

        mockMvc.perform(get("/admin/eventos").cookie(cookieOf(organizerB)))
                .andExpect(model().attribute("events", hasSize(1)))
                .andExpect(content().string(containsString("Evento de B")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Evento de A"))));
    }

    @Test
    @DisplayName("Un organizador sin eventos ve un estado vacío claro (200), no un error")
    void myEventsEmptyState() throws Exception {
        Organizer empty = organizerRepository.save(Organizer.builder().email("vacio@test.com").build());

        mockMvc.perform(get("/admin/eventos").cookie(cookieOf(empty)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("events", hasSize(0)))
                .andExpect(content().string(containsString("Todavía no tenés eventos")));
    }

    @Test
    @DisplayName("Mis eventos exige sesión de organizador: sin JWT redirige al login")
    void myEventsRequiresLogin() throws Exception {
        mockMvc.perform(get("/admin/eventos"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    @DisplayName("/admin/dashboard ya no existe: el panel siempre lleva el evento en la ruta")
    void dashboardWithoutEventIsGone() throws Exception {
        mockMvc.perform(get("/admin/dashboard").cookie(cookieOf(organizerA)))
                .andExpect(status().isNotFound());
    }

    // ---------- Panel propio ----------

    @Test
    @DisplayName("El panel de un evento propio muestra solo sus fotos y mensajes")
    void ownDashboardShowsOnlyOwnData() throws Exception {
        mockMvc.perform(get("/admin/eventos/" + eventA.getSlug()).cookie(cookieOf(organizerA)))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/dashboard"))
                .andExpect(model().attribute("totalPhotos", 1L))
                .andExpect(model().attribute("guestMenuUrl", containsString("/e/" + eventA.getSlug())))
                .andExpect(content().string(containsString(photoA.getId().toString())))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(photoB.getId().toString()))));
    }

    // ---------- Organizador A contra el evento de B ----------

    @Test
    @DisplayName("A contra el panel HTML de B -> 404 idéntico al de un slug inexistente")
    void foreignDashboardIsIndistinguishableFromMissing() throws Exception {
        String foreign = mockMvc.perform(get("/admin/eventos/" + eventB.getSlug()).cookie(cookieOf(organizerA)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String missing = mockMvc.perform(get("/admin/eventos/no-existe-zzzzzzzz").cookie(cookieOf(organizerA)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(foreign).isEqualTo(missing).contains("Evento no encontrado");
        assertThat(foreign).doesNotContain(eventB.getName());
    }

    @Test
    @DisplayName("A contra CADA endpoint admin del evento de B -> 404 con el mismo cuerpo que un slug inexistente")
    void everyAdminEndpointHidesForeignEvents() throws Exception {
        String foreignSlug = eventB.getSlug();
        String missingSlug = "no-existe-zzzzzzzz";

        for (String slug : new String[]{foreignSlug, missingSlug}) {
            for (MockHttpServletRequestBuilder request : adminRequests(slug)) {
                mockMvc.perform(request.cookie(cookieOf(organizerA)))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.status").value(404))
                        .andExpect(jsonPath("$.message").value(NOT_FOUND_MESSAGE));
            }
        }

        // Nada del evento de B se tocó.
        assertThat(photoRepository.existsById(photoB.getId())).isTrue();
        assertThat(messageRepository.existsById(messageB.getId())).isTrue();
        assertThat(commentRepository.existsById(commentB.getId())).isTrue();
        assertThat(eventRepository.findById(eventB.getId()).orElseThrow().isActive()).isTrue();
    }

    private java.util.List<MockHttpServletRequestBuilder> adminRequests(String slug) {
        String base = "/api/v1/admin/events/" + slug;
        return java.util.List.of(
                get(base + "/photos"),
                delete(base + "/photos/" + photoB.getId()),
                get(base + "/photos/" + photoB.getId() + "/download"),
                get(base + "/photos/download-zip"),
                delete(base + "/messages/" + messageB.getId()),
                delete(base + "/comments/" + commentB.getId()),
                patch(base + "/toggle-status"),
                get(base + "/libro-de-visitas.pdf"));
    }

    @Test
    @DisplayName("IDOR: slug propio + photoId/messageId/commentId de otro evento -> 404 y el recurso ajeno sigue en pie")
    void ownSlugWithForeignChildIdIsNotFound() throws Exception {
        String base = "/api/v1/admin/events/" + eventA.getSlug();
        Cookie cookie = cookieOf(organizerA);

        mockMvc.perform(delete(base + "/photos/" + photoB.getId()).cookie(cookie)).andExpect(status().isNotFound());
        mockMvc.perform(get(base + "/photos/" + photoB.getId() + "/download").cookie(cookie)).andExpect(status().isNotFound());
        mockMvc.perform(delete(base + "/messages/" + messageB.getId()).cookie(cookie)).andExpect(status().isNotFound());
        mockMvc.perform(delete(base + "/comments/" + commentB.getId()).cookie(cookie)).andExpect(status().isNotFound());

        assertThat(photoRepository.existsById(photoB.getId())).isTrue();
        assertThat(messageRepository.existsById(messageB.getId())).isTrue();
        assertThat(commentRepository.existsById(commentB.getId())).isTrue();
    }

    @Test
    @DisplayName("Slug propio + id inexistente da el mismo 404 que un id de otro evento")
    void missingChildIdMatchesForeignChildId() throws Exception {
        String base = "/api/v1/admin/events/" + eventA.getSlug();
        String foreign = mockMvc.perform(delete(base + "/photos/" + photoB.getId()).cookie(cookieOf(organizerA)))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String missing = mockMvc.perform(delete(base + "/photos/" + UUID.randomUUID()).cookie(cookieOf(organizerA)))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        // Mismo cuerpo salvo el timestamp y el id pedido: no se distingue "ajeno" de "inexistente".
        assertThat(normalized(foreign)).isEqualTo(normalized(missing));
    }

    private static String normalized(String body) {
        return body.replaceAll("\"timestamp\":\"[^\"]*\",?", "").replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "ID");
    }

    @Test
    @DisplayName("El ZIP de un evento propio ignora los photoIds de otro evento")
    void zipIgnoresForeignPhotoIds() throws Exception {
        byte[] zip = mockMvc.perform(get("/api/v1/admin/events/" + eventA.getSlug() + "/photos/download-zip")
                        .param("photoIds", photoB.getId().toString()).cookie(cookieOf(organizerA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        // ZIP vacío (solo el terminador): ninguna entrada de la foto ajena.
        assertThat(new String(zip, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("b/1");
    }

    @Test
    @DisplayName("Con el evento propio, cada endpoint admin funciona (control de que el 404 es por el dueño y no por la ruta)")
    void ownEventEndpointsWork() throws Exception {
        String base = "/api/v1/admin/events/" + eventA.getSlug();
        Cookie cookie = cookieOf(organizerA);

        mockMvc.perform(get(base + "/photos").cookie(cookie)).andExpect(status().isOk());
        mockMvc.perform(patch(base + "/toggle-status").cookie(cookie)).andExpect(status().isOk());
        mockMvc.perform(delete(base + "/comments/" + commentA.getId()).cookie(cookie)).andExpect(status().isNoContent());
        mockMvc.perform(delete(base + "/messages/" + messageA.getId()).cookie(cookie)).andExpect(status().isNoContent());
        mockMvc.perform(delete(base + "/photos/" + photoA.getId()).cookie(cookie)).andExpect(status().isNoContent());
        assertThat(photoRepository.existsById(photoA.getId())).isFalse();
    }

    @Test
    @DisplayName("Las rutas viejas sin evento (/api/v1/admin/photos, /messages/{id}, /comments/{id}) ya no existen")
    void legacyEventlessRoutesAreGone() throws Exception {
        Cookie cookie = cookieOf(organizerA);
        mockMvc.perform(get("/api/v1/admin/photos").cookie(cookie)).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/admin/photos/" + photoA.getId()).cookie(cookie)).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/admin/messages/" + UUID.randomUUID()).cookie(cookie)).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/admin/comments/" + UUID.randomUUID()).cookie(cookie)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/admin/photos/download-zip").param("slug", eventA.getSlug()).cookie(cookie)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/admin/photos/events/" + eventA.getSlug() + "/download-zip").cookie(cookie)).andExpect(status().isNotFound());
    }
}
