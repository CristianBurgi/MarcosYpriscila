package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.common.config.JwtAuthenticationFilter;
import com.tuapp.eventfoto.common.config.JwtTokenProvider;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.testsupport.MutableClock;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Fase 9.8: checklist previa del panel y descargas del kit. Cada casilla se guarda en SU evento, el organizador de
 * otro evento recibe 404 (checklist y PDF), una clave desconocida da 400, y la checklist se ve hasta el cierre de la
 * subida (con el reloj fijo) y nunca en un evento vencido.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MutableClock.Config.class)
class ChecklistTest {

    private static final LocalDate EVENT_DATE = LocalDate.parse("2026-11-14");

    @Autowired private MockMvc mockMvc;
    @Autowired private EventRepository eventRepository;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private MutableClock clock;

    private Event eventA;
    private Event eventB;
    private Cookie organizerA;
    private Cookie organizerB;

    @BeforeEach
    void setUp() {
        cleanUp();
        clock.setArgentina(LocalDateTime.parse("2026-11-10T12:00"));
        Organizer a = organizerRepository.save(Organizer.builder().email("checklist-a@test.com").build());
        Organizer b = organizerRepository.save(Organizer.builder().email("checklist-b@test.com").build());
        eventA = eventRepository.save(event(a, "checklist-a-k7m2xq9p"));
        eventB = eventRepository.save(event(b, "checklist-b-x4h8wt2n"));
        organizerA = cookieOf(a);
        organizerB = cookieOf(b);
    }

    @AfterEach
    void cleanUp() {
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
    }

    private static Event event(Organizer organizer, String slug) {
        return Event.builder().organizer(organizer).name("Evento " + slug).slug(slug).eventDate(EVENT_DATE)
                .isActive(true).origin(EventOrigin.PAID).wizardCompletedAt(Instant.parse("2026-10-01T12:00:00Z")).build();
    }

    private Cookie cookieOf(Organizer organizer) {
        return new Cookie(JwtAuthenticationFilter.ORGANIZER_COOKIE_NAME,
                jwtTokenProvider.generateOrganizerToken(organizer.getId(), organizer.getEmail(), organizer.getTokenVersion()));
    }

    private static String checklist(Event event, String item) {
        return "/api/v1/admin/events/" + event.getSlug() + "/checklist/" + item;
    }

    private short storedChecklist(Event event) {
        return eventRepository.findChecklist(event.getId());
    }

    @Test
    @DisplayName("Marcar y desmarcar: se guarda en su evento, es idempotente y el panel la muestra marcada al recargar")
    void markAndUnmarkPersistPerEvent() throws Exception {
        mockMvc.perform(put(checklist(eventA, "pantalla")).cookie(organizerA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.marked[0]").value("pantalla"));
        mockMvc.perform(put(checklist(eventA, "dj")).cookie(organizerA)).andExpect(status().isOk());
        mockMvc.perform(put(checklist(eventA, "dj")).cookie(organizerA)) // dos veces: sigue marcada una sola vez
                .andExpect(jsonPath("$.marked.length()").value(2));

        assertThat(storedChecklist(eventA)).isEqualTo((short) (ChecklistItem.PANTALLA.mask | ChecklistItem.DJ.mask));
        assertThat(storedChecklist(eventB)).as("el evento B no se toca").isZero();

        mockMvc.perform(get("/admin/eventos/" + eventA.getSlug()).cookie(organizerA))
                .andExpect(content().string(containsString("data-item=\"pantalla\" checked")))
                .andExpect(content().string(containsString("data-item=\"dj\" checked")))
                .andExpect(content().string(containsString("data-item=\"tarjetas\">")));
        String panelB = mockMvc.perform(get("/admin/eventos/" + eventB.getSlug()).cookie(organizerB))
                .andReturn().getResponse().getContentAsString();
        assertThat(panelB).contains("id=\"checklistCard\"").doesNotContainPattern("data-item=\"\\w+\" checked");

        mockMvc.perform(delete(checklist(eventA, "pantalla")).cookie(organizerA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.marked[0]").value("dj"));
        mockMvc.perform(delete(checklist(eventA, "pantalla")).cookie(organizerA)).andExpect(status().isOk());
        assertThat(storedChecklist(eventA)).isEqualTo((short) ChecklistItem.DJ.mask);
    }

    @Test
    @DisplayName("Una clave desconocida da 400 y no toca nada")
    void unknownItemIs400() throws Exception {
        for (String item : new String[]{"cualquiera", "PANTALLA", "0"}) {
            mockMvc.perform(put(checklist(eventA, item)).cookie(organizerA)).andExpect(status().isBadRequest());
            mockMvc.perform(delete(checklist(eventA, item)).cookie(organizerA)).andExpect(status().isBadRequest());
        }
        assertThat(storedChecklist(eventA)).isZero();
    }

    @Test
    @DisplayName("El organizador B contra la checklist y los PDF del evento A: 404, y A queda igual")
    void otherOrganizerGets404() throws Exception {
        for (String method : new String[]{"PUT", "DELETE"}) {
            mockMvc.perform((method.equals("PUT") ? put(checklist(eventA, "pantalla")) : delete(checklist(eventA, "pantalla"))).cookie(organizerB))
                    .andExpect(status().isNotFound());
        }
        for (String pdf : new String[]{"tarjetas.pdf", "cartel.pdf"}) {
            mockMvc.perform(get("/api/v1/admin/events/" + eventA.getSlug() + "/" + pdf).cookie(organizerB)).andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/admin/events/" + eventA.getSlug() + "/" + pdf).cookie(organizerA))
                    .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF))
                    .andExpect(header().string("Content-Disposition", containsString("attachment")));
        }
        assertThat(storedChecklist(eventA)).isZero();
    }

    @Test
    @DisplayName("Con el reloj fijo: se ve hasta el último instante antes del cierre de la subida, y después se oculta")
    void visibleUntilUploadWindowCloses() throws Exception {
        String panel = "/admin/eventos/" + eventA.getSlug();
        // Cierre: 00:00 de dos días después del evento (16/11), hora de Argentina.
        clock.setArgentina(LocalDateTime.parse("2026-11-15T23:59:59"));
        mockMvc.perform(get(panel).cookie(organizerA))
                .andExpect(status().isOk()).andExpect(content().string(containsString("id=\"checklistCard\"")))
                .andExpect(content().string(containsString("id=\"kitCard\"")));

        clock.setArgentina(LocalDateTime.parse("2026-11-16T00:00"));
        mockMvc.perform(get(panel).cookie(organizerA))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("id=\"checklistCard\""))))
                .andExpect(content().string(containsString("id=\"kitCard\"")));
    }

    @Test
    @DisplayName("Evento vencido: el panel muestra 'no disponible', sin checklist")
    void expiredEventShowsNoChecklist() throws Exception {
        clock.setArgentina(LocalDateTime.parse("2026-12-14T12:00")); // fecha + 30 días: el álbum venció
        String html = mockMvc.perform(get("/admin/eventos/" + eventA.getSlug()).cookie(organizerA))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("checklistCard");
        mockMvc.perform(put(checklist(eventA, "pantalla")).cookie(organizerA)).andExpect(status().isGone());
    }
}
