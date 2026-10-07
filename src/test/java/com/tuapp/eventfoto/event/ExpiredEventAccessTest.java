package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.common.config.JwtAuthenticationFilter;
import com.tuapp.eventfoto.common.config.JwtTokenProvider;
import com.tuapp.eventfoto.common.email.EmailService;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.storage.StorageService;
import com.tuapp.eventfoto.testsupport.MutableClock;
import com.tuapp.eventfoto.testsupport.TestEvents;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 9.6: álbum vencido (fecha de borrado alcanzada, aunque el job todavía no haya corrido). El evento venció el
 * 01/12 (evento el 01/11); "ahora" es el 01/12 a las 10:00 en Argentina. Mismo contexto que EventLifecycleTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MutableClock.Config.class)
class ExpiredEventAccessTest {

    private static final String EXPIRED = "vencido-k7m2xq9p";
    private static final String UNAVAILABLE = "Este álbum ya no está disponible.";

    @Autowired private MockMvc mockMvc;
    @Autowired private MutableClock clock;
    @Autowired private EventRepository eventRepository;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @SpyBean private StorageService storageService;
    @SpyBean private EmailService emailService;

    private Organizer owner;
    private Organizer other;
    private Event expired;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
        owner = organizerRepository.save(Organizer.builder().email("duena@test.com").build());
        other = organizerRepository.save(Organizer.builder().email("otra@test.com").build());
        expired = eventRepository.save(TestEvents.base(owner, EXPIRED).eventDate(LocalDate.of(2026, 11, 1)).build());
        eventRepository.save(TestEvents.base(owner, "vigente-k7m2xq9p").eventDate(LocalDate.of(2026, 11, 20)).build());
        clock.setArgentina(LocalDateTime.parse("2026-12-01T10:00:00"));
    }

    @AfterEach
    void cleanUp() {
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
    }

    private Cookie cookieOf(Organizer organizer) {
        return new Cookie(JwtAuthenticationFilter.ORGANIZER_COOKIE_NAME,
                jwtTokenProvider.generateOrganizerToken(organizer.getId(), organizer.getEmail(), organizer.getTokenVersion()));
    }

    @Test
    @DisplayName("Invitado: las cinco páginas muestran 'no disponible' (410, no el 404 de evento inexistente) y la API pública responde 410 EVENT_EXPIRED")
    void guestSide() throws Exception {
        for (String suffix : new String[]{"", "/subir", "/album", "/mensajes", "/pantalla"}) {
            mockMvc.perform(get("/e/" + EXPIRED + suffix))
                    .andExpect(status().isGone())
                    .andExpect(content().string(containsString(UNAVAILABLE)))
                    .andExpect(content().string(not(containsString("Volver a Mis eventos"))));
        }
        for (String path : new String[]{"", "/photos", "/messages", "/guest-quota?token=x", "/qr", "/stream"}) {
            mockMvc.perform(get("/api/v1/events/" + EXPIRED + path))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.error").value("EVENT_EXPIRED"));
        }
        mockMvc.perform(post("/api/v1/events/" + EXPIRED + "/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorName\":\"Caro\",\"text\":\"Hola\",\"guestToken\":\"t\"}"))
                .andExpect(status().isGone());
        // Un slug que no existe sigue siendo el 404 de siempre, y el evento vigente se sirve.
        mockMvc.perform(get("/e/no-existe-k7m2xq9p")).andExpect(status().isNotFound());
        mockMvc.perform(get("/e/vigente-k7m2xq9p")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("El día anterior a la fecha de borrado el álbum todavía se ve (y se descarga), aunque la subida esté cerrada")
    void stillVisibleTheDayBefore() throws Exception {
        clock.setArgentina(LocalDateTime.parse("2026-11-30T23:59:00"));
        mockMvc.perform(get("/e/" + EXPIRED + "/album")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/events/" + EXPIRED + "/photos")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/events/" + EXPIRED)).andExpect(jsonPath("$.uploadStatus").value("CLOSED"));
        mockMvc.perform(get("/moderar/" + expired.getModeratorToken())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Moderador: el link da el mismo 404 de siempre (vista y API)")
    void moderatorSide() throws Exception {
        mockMvc.perform(get("/moderar/" + expired.getModeratorToken())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/moderate/" + expired.getModeratorToken() + "/photos")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Organizador: 'Vencido' en Mis eventos, pantalla de no disponible sin panel, API del panel 410; B contra A sigue en 404")
    void organizerSide() throws Exception {
        mockMvc.perform(get("/admin/eventos").cookie(cookieOf(owner)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Vencido")));

        mockMvc.perform(get("/admin/eventos/" + EXPIRED).cookie(cookieOf(owner)))
                .andExpect(status().isGone())
                .andExpect(content().string(containsString(UNAVAILABLE)))
                .andExpect(content().string(containsString("Volver a Mis eventos")))
                .andExpect(content().string(not(containsString("moderatorCard"))));
        mockMvc.perform(get("/api/v1/admin/events/" + EXPIRED + "/photos").cookie(cookieOf(owner)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error").value("EVENT_EXPIRED"));

        mockMvc.perform(get("/admin/eventos/" + EXPIRED).cookie(cookieOf(other))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/admin/events/" + EXPIRED + "/photos").cookie(cookieOf(other))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Panel vigente: la línea de fechas, en castellano y en hora de Argentina")
    void dashboardShowsTheRules() throws Exception {
        mockMvc.perform(get("/admin/eventos/vigente-k7m2xq9p").cookie(cookieOf(owner)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "Tus invitados pueden subir fotos del jueves 19 de noviembre 00:00 al sábado 21 de noviembre 23:59.")))
                .andExpect(content().string(containsString("Tu álbum se borra el domingo 20 de diciembre.")));
    }
}
