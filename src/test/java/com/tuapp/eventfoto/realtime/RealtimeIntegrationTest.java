package com.tuapp.eventfoto.realtime;

import com.tuapp.eventfoto.comment.CommentRepository;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.message.MessageRepository;
import com.tuapp.eventfoto.message.MessageService;
import com.tuapp.eventfoto.message.dto.CreateMessageRequestDTO;
import com.tuapp.eventfoto.photo.Photo;
import com.tuapp.eventfoto.photo.PhotoRepository;
import com.tuapp.eventfoto.photo.PhotoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RealtimeIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private PhotoRepository photoRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private SseBroadcaster sseBroadcaster;

    @Autowired
    private PhotoService photoService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private OrganizerRepository organizerRepository;

    private Event event1;
    private Event event2;

    @BeforeEach
    void setUp() {
        commentRepository.deleteAll();
        messageRepository.deleteAll();
        photoRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();

        Organizer organizer = organizerRepository.save(Organizer.builder().email("realtime@test.com").build());

        event1 = eventRepository.save(Event.builder()
                .organizer(organizer)
                .name("Evento de Prueba")
                .slug("evento-demo-k7m2xq9p")
                .eventDate(LocalDate.now().plusDays(1))
                .uploadDeadline(Instant.now().plusSeconds(864000))
                .isActive(true)
                .origin(EventOrigin.PAID)
                .build());

        event2 = eventRepository.save(Event.builder()
                .organizer(organizer)
                .name("Cumpleaños de Prueba")
                .slug("cumple-prueba")
                .eventDate(LocalDate.now().plusDays(1))
                .uploadDeadline(Instant.now().plusSeconds(864000))
                .isActive(true)
                .origin(EventOrigin.PAID)
                .build());
    }

    @Test
    @DisplayName("GET /api/v1/events/{slug}/stream - Debe retornar 200 OK con Content-Type text/event-stream")
    void shouldOpenSseStream() throws Exception {
        mockMvc.perform(get("/api/v1/events/evento-demo-k7m2xq9p/stream"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    }

    @Test
    @DisplayName("GET /api/v1/events/{slug}/stream con un slug inexistente responde 404 JSON aunque el cliente pida text/event-stream (EventSource)")
    void unknownSlugStreamIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/events/no-existe-zzzzzzzz/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("Debe aislar las notificaciones SSE por evento (las notificaciones de evento 1 no llegan a evento 2)")
    void shouldIsolateEventsByEventId() {
        SseEmitter emitter1 = sseBroadcaster.subscribe(event1.getId());
        SseEmitter emitter2 = sseBroadcaster.subscribe(event2.getId());

        assertEquals(1, sseBroadcaster.getActiveSubscribersCount(event1.getId()));
        assertEquals(1, sseBroadcaster.getActiveSubscribersCount(event2.getId()));

        Photo photo = photoRepository.save(Photo.builder()
                .event(event1)
                .storageKey("photos/sample.jpg")
                .build());

        // Al borrar la foto en event1, emite PHOTO_DELETED solo a event1
        photoService.deletePhoto(event1.getId(), photo.getId());

        // Al crear un mensaje en event2, emite solo a event2
        messageService.addMessage("cumple-prueba", new CreateMessageRequestDTO("Invitado", "¡Feliz cumple!"), "127.0.0.1");

        emitter1.complete();
        emitter2.complete();
    }
}
