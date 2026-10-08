package com.tuapp.eventfoto.superadmin;

import com.tuapp.eventfoto.common.config.JwtAuthenticationFilter;
import com.tuapp.eventfoto.common.config.JwtTokenProvider;
import com.tuapp.eventfoto.common.config.RateLimiterService;
import com.tuapp.eventfoto.common.email.EmailService;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.organizer.OrganizerTokenRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Los POST del superadmin exigen JSON (415 si no). Este test cuida que las páginas lo manden:
 * <ol>
 *   <li>cada fetch de las plantillas de superadmin lleva {@code 'Content-Type': 'application/json'} (si una página
 *       nueva lo olvida, falla acá y no en producción);</li>
 *   <li>cada request se reproduce tal como la arma el JS de su página (headers y cuerpo) y no da 415.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SuperadminPageRequestsTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates/superadmin");
    private static final Pattern FETCH = Pattern.compile("fetch\\(([^,]+),\\s*\\{(.*?)}\\)", Pattern.DOTALL);
    private static final Pattern JSON_HEADER = Pattern.compile("['\"]Content-Type['\"]\\s*:\\s*['\"]application/json['\"]");

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private RateLimiterService rateLimiterService;
    @Autowired private EventRepository eventRepository;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private OrganizerTokenRepository organizerTokenRepository;
    @MockBean private EmailService emailService;

    @AfterEach
    void cleanUp() {
        rateLimiterService.resetRateLimits();
        organizerTokenRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
    }

    private Cookie superadminCookie() {
        return new Cookie(JwtAuthenticationFilter.SUPERADMIN_COOKIE_NAME, jwtTokenProvider.generateSuperadminToken("superadmin@boda.com"));
    }

    @Test
    @DisplayName("Cada fetch de las plantillas del superadmin manda Content-Type: application/json")
    void everyTemplateFetchSendsJson() throws Exception {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(TEMPLATES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                Matcher fetch = FETCH.matcher(Files.readString(file));
                while (fetch.find()) {
                    String call = file.getFileName() + ": fetch(" + fetch.group(1).trim() + ")";
                    assertThat(fetch.group(2)).as(call + " sin Content-Type JSON: el endpoint respondería 415").containsPattern(JSON_HEADER);
                    found.add(call);
                }
            }
        }
        System.out.println("FETCH del superadmin con JSON: " + found);
        // login, crear evento sin costo, cerrar sesión (menú) y el diálogo de acciones del listado.
        assertThat(found).hasSize(4);
    }

    @Test
    @DisplayName("login.html: POST /auth/login como lo arma la página -> 200")
    void loginPage() throws Exception {
        mockMvc.perform(post("/api/v1/superadmin/auth/login")
                        .header("Content-Type", "application/json")
                        .content("{\"email\":\"superadmin@boda.com\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("new-event.html: POST /events/free-event como lo arma la página -> 200")
    void newEventPage() throws Exception {
        mockMvc.perform(post("/api/v1/superadmin/events/free-event").cookie(superadminCookie())
                        .header("Content-Type", "application/json")
                        .content("{\"email\":\"familiar@test.com\",\"eventName\":\"Cumple\",\"reason\":\"Familiar\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("nav.html (Cerrar sesión): POST /auth/logout con body '{}' y JSON -> 204; sin Content-Type sería 415")
    void logoutButton() throws Exception {
        mockMvc.perform(post("/api/v1/superadmin/auth/logout").cookie(superadminCookie())
                        .header("Content-Type", "application/json").content("{}"))
                .andExpect(status().isNoContent());
        // Lo que pasaría con un fetch sin cuerpo y sin Content-Type: por eso el botón manda los dos.
        mockMvc.perform(post("/api/v1/superadmin/auth/logout").cookie(superadminCookie()))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("events.html (diálogo de acciones): POST con JSON.stringify({}) -> no 415")
    void eventsDialog() throws Exception {
        mockMvc.perform(post("/api/v1/superadmin/lifecycle/run").cookie(superadminCookie())
                        .header("Content-Type", "application/json").content("{}"))
                .andExpect(status().isOk());
    }
}
