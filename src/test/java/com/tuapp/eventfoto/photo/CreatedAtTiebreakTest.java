package com.tuapp.eventfoto.photo;

import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.message.Message;
import com.tuapp.eventfoto.message.MessageRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.testsupport.TestEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fotos y mensajes con el mismo created_at (una ráfaga de subidas en el mismo instante) salen en un orden fijo:
 * id como segundo criterio. Sin desempate, el orden de un empate es el que la base quiera y la paginación puede repetir
 * o saltear filas entre páginas.
 */
@SpringBootTest
@ActiveProfiles("test")
class CreatedAtTiebreakTest {

    private static final int N = 10;
    private static final Instant SAME = Instant.parse("2026-11-01T22:00:00Z");

    @Autowired private OrganizerRepository organizers;
    @Autowired private EventRepository events;
    @Autowired private PhotoRepository photos;
    @Autowired private MessageRepository messages;

    private Organizer organizer;
    private Event event;

    @BeforeEach
    void setUp() {
        organizer = organizers.save(Organizer.builder().email("empate@test.com").build());
        event = events.save(TestEvents.base(organizer, "empate-k7m2xq9p").build());
        for (int i = 0; i < N; i++) {
            photos.save(Photo.builder().event(event).storageKey("k-" + i).createdAt(SAME).build());
            messages.save(Message.builder().event(event).authorName("A").text("m" + i).isApproved(true).createdAt(SAME).build());
        }
    }

    /** Solo lo propio: la H2 de los tests es compartida y puede tener datos de otras clases. */
    @AfterEach
    void cleanUp() {
        messages.deleteAll(messages.findByEventIdOrderByCreatedAtDescIdDesc(event.getId(), PageRequest.of(0, 100)).getContent());
        photos.deleteAll(photos.findByEventIdOrderByCreatedAtDescIdDesc(event.getId(), PageRequest.of(0, 100)).getContent());
        events.delete(event);
        organizers.delete(organizer);
    }

    private static List<UUID> sorted(List<UUID> ids, boolean desc) {
        // Como la base (bytes sin signo = texto hex); UUID.compareTo compara longs CON signo y no coincide.
        Comparator<UUID> byId = Comparator.comparing(UUID::toString);
        return ids.stream().sorted(desc ? byId.reversed() : byId).toList();
    }

    @Test
    @DisplayName("Empate en created_at: fotos y mensajes desempatan por id (desc en los listados, asc en el libro de visitas)")
    void tiesAreBrokenById() {
        PageRequest all = PageRequest.of(0, 50);
        List<UUID> photoIds = photos.findByEventIdOrderByCreatedAtDescIdDesc(event.getId(), all).map(Photo::getId).toList();
        assertThat(photoIds).hasSize(N).containsExactlyElementsOf(sorted(photoIds, true));

        List<List<UUID>> desc = List.of(
                messages.findByEventIdAndIsApprovedTrueOrderByCreatedAtDescIdDesc(event.getId()).stream().map(Message::getId).toList(),
                messages.findByEventIdAndIsApprovedTrueOrderByCreatedAtDescIdDesc(event.getId(), all).map(Message::getId).toList(),
                messages.findByEventIdOrderByCreatedAtDescIdDesc(event.getId(), all).map(Message::getId).toList());
        for (List<UUID> ids : desc) {
            assertThat(ids).hasSize(N).containsExactlyElementsOf(sorted(ids, true));
        }
        List<UUID> guestbook = messages.findByEventIdAndIsApprovedTrueOrderByCreatedAtAscIdAsc(event.getId()).stream().map(Message::getId).toList();
        assertThat(guestbook).hasSize(N).containsExactlyElementsOf(sorted(guestbook, false));
    }

    @Test
    @DisplayName("Paginando de a 3 sobre un empate: ninguna foto se repite ni se pierde entre páginas")
    void paginationOverATieIsStable() {
        List<UUID> paged = new java.util.ArrayList<>();
        for (int page = 0; page * 3 < N; page++) {
            photos.findByEventIdOrderByCreatedAtDescIdDesc(event.getId(), PageRequest.of(page, 3)).forEach(p -> paged.add(p.getId()));
        }
        assertThat(paged).hasSize(N).doesNotHaveDuplicates();
    }
}
