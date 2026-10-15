package com.tuapp.eventfoto.photo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuapp.eventfoto.checkout.PaymentLookupGateway;
import com.tuapp.eventfoto.comment.Comment;
import com.tuapp.eventfoto.comment.CommentRepository;
import com.tuapp.eventfoto.common.email.EmailService;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.testsupport.MutableClock;
import com.tuapp.eventfoto.testsupport.TestEvents;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Auditoría #7: el listado de fotos (álbum, pantalla y panel usan PhotoService.getPhotos) traía los comentarios con
 * una consulta por foto: con size=100, 101 consultas por página. Ahora son las mismas consultas tenga la página 3 o
 * 60 fotos, y la respuesta no cambia (comentarios aprobados, del más nuevo al más viejo, con el id como desempate).
 *
 * Mismo contexto que SuperadminPanelTest (por eso el reloj y los mocks de MP y de mails, que este test no usa): un contexto nuevo
 * más hace que la caché de Spring cierre otro, y su create-drop vacía la H2 compartida debajo de los demás.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MutableClock.Config.class)
class PhotoListingQueryCountTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private OrganizerRepository organizers;
    @Autowired private EventRepository events;
    @Autowired private PhotoRepository photos;
    @Autowired private CommentRepository comments;
    @Autowired private MutableClock clock;
    @MockBean private PaymentLookupGateway gateway;
    @MockBean private EmailService emailService;

    private Organizer organizer;
    private Event event;

    @BeforeEach
    void setUp() {
        clock.set(Instant.now());
        organizer = organizers.save(Organizer.builder().email("listado-" + UUID.randomUUID() + "@test.com").build());
        event = events.save(TestEvents.base(organizer, "listado-" + UUID.randomUUID().toString().substring(0, 8)).build());
    }

    @AfterEach
    void cleanUp() {
        List<Photo> eventPhotos = photos.findAll().stream().filter(p -> p.getEvent().getId().equals(event.getId())).toList();
        eventPhotos.forEach(p -> comments.deleteAll(comments.findAll().stream().filter(c -> c.getPhoto().getId().equals(p.getId())).toList()));
        photos.deleteAll(eventPhotos);
        events.delete(event);
        organizers.delete(organizer);
    }

    @Test
    @DisplayName("Las consultas del listado no dependen de la cantidad de fotos de la página")
    void queryCountDoesNotDependOnPhotoCount() throws Exception {
        addPhotosWithComments(3);
        long few = statementsForListing();

        addPhotosWithComments(57);
        long many = statementsForListing();

        System.out.println("LISTADO DE FOTOS consultas: 3 fotos -> " + few + ", 60 fotos -> " + many);
        assertThat(many).isEqualTo(few);
    }

    @Test
    @DisplayName("Cada foto trae sus comentarios aprobados en el mismo orden que la consulta por foto (más nuevo primero, id de desempate)")
    void commentsKeepTheirOrderAndFilter() throws Exception {
        Instant t = Instant.parse("2026-10-10T20:00:00Z");
        Photo withComments = photos.save(Photo.builder().event(event).storageKey("k-orden-1").createdAt(t).build());
        Photo empty = photos.save(Photo.builder().event(event).storageKey("k-orden-2").createdAt(t.plusSeconds(1)).build());
        comments.save(Comment.builder().photo(withComments).authorName("A").text("viejo").createdAt(t).build());
        comments.save(Comment.builder().photo(withComments).authorName("B").text("empate 1").createdAt(t.plusSeconds(60)).build());
        comments.save(Comment.builder().photo(withComments).authorName("C").text("empate 2").createdAt(t.plusSeconds(60)).build());
        comments.save(Comment.builder().photo(withComments).authorName("D").text("nuevo").createdAt(t.plusSeconds(120)).build());
        comments.save(Comment.builder().photo(withComments).authorName("E").text("no aprobado").isApproved(false).createdAt(t.plusSeconds(180)).build());

        JsonNode content = listing().get("content");

        assertThat(content).hasSize(2);
        for (JsonNode photo : content) {
            UUID photoId = UUID.fromString(photo.get("id").asText());
            List<String> expected = comments.findByPhotoIdAndIsApprovedTrueOrderByCreatedAtDescIdDesc(photoId).stream()
                    .map(c -> c.getId().toString()).toList();
            List<String> actual = new ArrayList<>();
            photo.get("comments").forEach(c -> actual.add(c.get("id").asText()));
            assertThat(actual).as("comentarios de la foto " + photoId).isEqualTo(expected);
            assertThat(photo.get("commentCount").asInt()).isEqualTo(expected.size());
        }
        assertThat(content.get(0).get("id").asText()).isEqualTo(empty.getId().toString());
        assertThat(content.get(1).get("comments")).hasSize(4);
    }

    private void addPhotosWithComments(int count) {
        for (int i = 0; i < count; i++) {
            Photo photo = photos.save(Photo.builder().event(event).storageKey("k-" + UUID.randomUUID()).build());
            comments.save(Comment.builder().photo(photo).authorName("A").text("uno").build());
            comments.save(Comment.builder().photo(photo).authorName("B").text("dos").build());
        }
    }

    private JsonNode listing() throws Exception {
        String body = mockMvc.perform(get("/api/v1/events/" + event.getSlug() + "/photos").param("size", "100"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body);
    }

    private long statementsForListing() throws Exception {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        listing();
        return stats.getPrepareStatementCount();
    }
}
