package com.tuapp.eventfoto.realtime;

import com.tuapp.eventfoto.event.UploadWindow;
import com.tuapp.eventfoto.common.config.JwtTokenProvider;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.photo.Photo;
import com.tuapp.eventfoto.photo.PhotoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 9.0: borrar una foto desde el panel (único control de moderación) tiene que
 * avisar por SSE a la pantalla del salón y al álbum para que la saquen al instante.
 * Recorre la cadena real: endpoint admin -> PhotoService -> SseBroadcaster -> emitter.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhotoDeletedSseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private PhotoRepository photoRepository;

    @Autowired
    private SseBroadcaster sseBroadcaster;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private OrganizerRepository organizerRepository;

    private Event event;
    private Organizer organizer;

    @BeforeEach
    void setUp() {
        photoRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
        organizer = organizerRepository.save(Organizer.builder().email("sse@test.com").build());
        event = eventRepository.save(Event.builder()
                .organizer(organizer)
                .name("Evento de Prueba")
                .slug("evento-demo-k7m2xq9p")
                .eventDate(LocalDate.now(UploadWindow.ZONE))
                .isActive(true)
                .origin(EventOrigin.PAID).wizardCompletedAt(Instant.now())
                .build());
    }

    @Test
    @DisplayName("DELETE /api/v1/admin/events/{slug}/photos/{id} emite PHOTO_DELETED con el photoId a los suscriptores del evento")
    void deletingPhotoEmitsPhotoDeletedEvent() throws Exception {
        Photo photo = photoRepository.save(Photo.builder()
                .event(event)
                .storageKey("photos/evento-demo-k7m2xq9p/borrar.jpg")
                .uploaderName("Tío Beto")
                .build());

        SseEmitter screen = mock(SseEmitter.class);
        sseBroadcaster.register(event.getId(), screen);
        clearInvocations(screen); // descarta el INIT

        mockMvc.perform(delete("/api/v1/admin/events/evento-demo-k7m2xq9p/photos/" + photo.getId())
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateOrganizerToken(organizer.getId(), organizer.getEmail(), organizer.getTokenVersion())))
                .andExpect(status().isNoContent());

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(screen, atLeastOnce()).send(captor.capture());
        List<String> sent = captor.getAllValues().stream().map(SseTestSupport::render).toList();

        assertThat(sent)
                .filteredOn(wire -> wire.contains("event:PHOTO_DELETED"))
                .singleElement()
                .satisfies(wire -> assertThat(wire).contains(photo.getId().toString()));
        assertThat(photoRepository.existsById(photo.getId())).isFalse();
    }
}
