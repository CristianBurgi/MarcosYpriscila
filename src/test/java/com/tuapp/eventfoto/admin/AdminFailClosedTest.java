package com.tuapp.eventfoto.admin;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tuapp.eventfoto.common.config.JwtAuthenticationFilter;
import com.tuapp.eventfoto.common.config.JwtTokenProvider;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventAccessInterceptor;
import com.tuapp.eventfoto.event.OwnedEvent;
import com.tuapp.eventfoto.event.OwnedEventArgumentResolver;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 9.1 Bloque 3: fallo cerrado. Un controller bajo /admin o /api/v1/admin SIN {slug} y
 * fuera de AdminRouteExceptions no queda abierto: responde 404 y deja un log.error con la
 * ruta. (El controller de prueba es una clase anidada importada solo acá: así no aparece en
 * el contexto de AdminRouteEnumerationTest, que justamente exige que no exista ninguno.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(AdminFailClosedTest.UnprotectedProbeController.class)
class AdminFailClosedTest {

    @RestController
    static class UnprotectedProbeController {
        @GetMapping("/api/v1/admin/probe-sin-slug")
        String api() {
            return "ABIERTO";
        }

        @GetMapping("/admin/probe-sin-slug")
        String view() {
            return "ABIERTO";
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private OwnedEventArgumentResolver resolver;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Cookie cookie;

    @BeforeEach
    void setUp() {
        organizerRepository.deleteAll();
        Organizer organizer = organizerRepository.save(Organizer.builder().email("fail-closed@test.com").build());
        cookie = new Cookie(JwtAuthenticationFilter.ORGANIZER_COOKIE_NAME,
                jwtTokenProvider.generateOrganizerToken(organizer.getId(), organizer.getEmail(), organizer.getTokenVersion()));
        logs.start();
        ((Logger) LoggerFactory.getLogger(EventAccessInterceptor.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(EventAccessInterceptor.class)).detachAppender(logs);
    }

    @Test
    @DisplayName("Ruta de la API del panel sin {slug} y fuera de las excepciones -> 404 + log.error con la ruta")
    void apiRouteWithoutSlugIsClosed() throws Exception {
        mockMvc.perform(get("/api/v1/admin/probe-sin-slug").cookie(cookie))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Evento no encontrado"));

        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("/api/v1/admin/probe-sin-slug").contains("AdminRouteExceptions");
        });
    }

    @Test
    @DisplayName("Vista del panel sin {slug} y fuera de las excepciones -> 404 + log.error con la ruta")
    void viewRouteWithoutSlugIsClosed() throws Exception {
        mockMvc.perform(get("/admin/probe-sin-slug").cookie(cookie))
                .andExpect(status().isNotFound());

        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("/admin/probe-sin-slug");
        });
    }

    @Test
    @DisplayName("Request bajo /admin sin controller (recurso estático / ruta inventada) -> 404 con log.warn, NO log.error (no debe llegar a Sentry)")
    void routeWithoutControllerIsClosedWithWarnNotError() throws Exception {
        mockMvc.perform(get("/admin/ruta-inventada.css").cookie(cookie))
                .andExpect(status().isNotFound());

        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("/admin/ruta-inventada.css");
        });
        assertThat(logs.list).noneMatch(event -> event.getLevel() == Level.ERROR);
    }

    @Test
    @DisplayName("@OwnedEvent sin el evento resuelto por el interceptor falla ruidosamente (IllegalStateException), nunca devuelve null")
    void ownedEventWithoutInterceptorAttributeFailsLoudly() throws Exception {
        MethodParameter parameter = new MethodParameter(
                SampleHandler.class.getDeclaredMethod("handler", Event.class), 0);
        ServletWebRequest requestWithoutAttribute = new ServletWebRequest(new org.springframework.mock.web.MockHttpServletRequest());

        assertThatThrownBy(() -> resolver.resolveArgument(parameter, null, requestWithoutAttribute, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EventAccessInterceptor");
    }

    static class SampleHandler {
        void handler(@OwnedEvent Event event) {
        }
    }
}
