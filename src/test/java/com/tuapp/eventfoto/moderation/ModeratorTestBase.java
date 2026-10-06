package com.tuapp.eventfoto.moderation;

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
import com.tuapp.eventfoto.storage.StorageService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Dos eventos con datos COMMITEADOS (sin @Transactional: cada request corre como en producción, con
 * open-in-view=false y sin una transacción de test que tape un acceso lazy). El storage está mockeado para
 * poder comprobar que el borrado quita el objeto.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class ModeratorTestBase {

    @Autowired protected MockMvc mockMvc;
    @Autowired protected OrganizerRepository organizerRepository;
    @Autowired protected EventRepository eventRepository;
    @Autowired protected PhotoRepository photoRepository;
    @Autowired protected MessageRepository messageRepository;
    @Autowired protected CommentRepository commentRepository;
    @Autowired protected JwtTokenProvider jwtTokenProvider;
    @Autowired protected ModeratorRateLimiter rateLimiter;
    @MockBean protected StorageService storageService;

    protected Organizer organizerA;
    protected Organizer organizerB;
    protected Event eventA;
    protected Event eventB;
    protected Photo photoA;
    protected Photo photoB;
    protected Message messageA;
    protected Message messageB;

    @BeforeEach
    void setUpEvents() {
        cleanUpEvents();
        rateLimiter.reset();
        when(storageService.generatePublicUrl(anyString())).thenAnswer(call -> "/uploads/" + call.getArgument(0));

        organizerA = organizerRepository.save(Organizer.builder().email("mod-a@test.com").build());
        organizerB = organizerRepository.save(Organizer.builder().email("mod-b@test.com").build());
        eventA = event(organizerA, "Evento A", "evento-mod-a-k7m2xq9p");
        eventB = event(organizerB, "Evento B", "evento-mod-b-x4h8wt2n");
        photoA = photo(eventA, "Ana");
        photoB = photo(eventB, "Beto");
        messageA = messageRepository.save(Message.builder().event(eventA).authorName("Ana").text("Mensaje de A").build());
        messageB = messageRepository.save(Message.builder().event(eventB).authorName("Beto").text("Mensaje de B").build());
    }

    @AfterEach
    void cleanUpEvents() {
        rateLimiter.reset();
        commentRepository.deleteAll();
        messageRepository.deleteAll();
        photoRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
    }

    protected Event event(Organizer organizer, String name, String slug) {
        return eventRepository.save(Event.builder()
                .organizer(organizer).name(name).slug(slug)
                .eventDate(LocalDate.now().plusDays(3)).uploadDeadline(Instant.now().plusSeconds(864000))
                .isActive(true).origin(EventOrigin.PAID).wizardCompletedAt(Instant.now()).build());
    }

    protected Photo photo(Event event, String uploader) {
        return photoRepository.save(Photo.builder().event(event)
                .storageKey("events/" + event.getId() + "/" + UUID.randomUUID() + ".jpg").uploaderName(uploader).build());
    }

    protected Cookie cookieOf(Organizer organizer) {
        return new Cookie(JwtAuthenticationFilter.ORGANIZER_COOKIE_NAME,
                jwtTokenProvider.generateOrganizerToken(organizer.getId(), organizer.getEmail(), organizer.getTokenVersion()));
    }

    protected String tokenOf(Event event) {
        return eventRepository.findById(event.getId()).orElseThrow().getModeratorToken();
    }

    protected static String api(String token, String suffix) {
        return "/api/v1/moderate/" + token + suffix;
    }
}
