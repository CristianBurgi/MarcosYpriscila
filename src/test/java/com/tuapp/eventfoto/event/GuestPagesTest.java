package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Fase 9.1 Bloque 2: las páginas de invitado y la pantalla del salón toman el evento de
 * /e/{slug}/..., el slug inexistente muestra "evento no encontrado", las URLs viejas
 * redirigen con 302 y toda la app manda Referrer-Policy.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class GuestPagesTest {

    private static final String SLUG = "evento-demo-k7m2xq9p";
    private static final String[] PAGE_SUFFIXES = {"", "/subir", "/album", "/mensajes", "/pantalla"};
    private static final Pattern ATTRIBUTE = Pattern.compile("\\b(?:src|href)=\"([^\"]*)\"");

    @Autowired private MockMvc mockMvc;
    @Autowired private EventRepository eventRepository;
    @Autowired private OrganizerRepository organizerRepository;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
        Organizer organizer = organizerRepository.save(Organizer.builder().email("guest-pages@test.com").build());
        eventRepository.save(Event.builder()
                .organizer(organizer).name("Evento Demo").slug(SLUG)
                .eventDate(LocalDate.now().plusDays(1))
                .uploadDeadline(Instant.now().plusSeconds(864000))
                .isActive(true).origin(EventOrigin.PAID).build());
    }

    @Test
    @DisplayName("Las cinco páginas bajo /e/{slug} responden 200 HTML y no llevan ningún slug escrito a mano")
    void pagesLoadFromTheEventUrl() throws Exception {
        for (String suffix : PAGE_SUFFIXES) {
            String html = mockMvc.perform(get("/e/" + SLUG + suffix))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/html"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(html).as(suffix).contains("/js/event-context.js");
            assertThat(html).as(suffix).doesNotContain("/images/");
            assertThat(html).as(suffix).doesNotContain("const SLUG = '");
        }
    }

    @Test
    @DisplayName("Assets bajo /e/{slug}/...: todo href/src es absoluto (no se rompe con el path) y responde 200")
    void assetsResolveUnderEventPath() throws Exception {
        for (String suffix : PAGE_SUFFIXES) {
            String html = mockMvc.perform(get("/e/" + SLUG + suffix))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            List<String> localAssets = new ArrayList<>();
            Matcher matcher = ATTRIBUTE.matcher(html);
            while (matcher.find()) {
                String url = matcher.group(1);
                if (url.isEmpty() || url.startsWith("#") || url.startsWith("data:") || url.startsWith("javascript:") || url.contains("${")
                        || url.startsWith("http://") || url.startsWith("https://") || url.startsWith("//")) {
                    continue; // fuentes/CDN externos y anclas
                }
                assertThat(url).as("ruta relativa en %s%s se rompería bajo /e/{slug}/", "/e/" + SLUG, suffix).startsWith("/");
                localAssets.add(url);
            }
            assertThat(localAssets).as("assets locales de " + suffix).isNotEmpty();
            for (String asset : localAssets) {
                mockMvc.perform(get(asset)).andExpect(status().isOk());
            }
        }
    }

    @Test
    @DisplayName("El JS compartido existe y deriva el slug de la URL")
    void eventContextScriptIsServed() throws Exception {
        mockMvc.perform(get("/js/event-context.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/e/")));
    }

    @Test
    @DisplayName("Slug inexistente en cualquiera de las páginas -> 404 con pantalla 'Evento no encontrado'")
    void unknownSlugShowsNotFoundPage() throws Exception {
        for (String suffix : PAGE_SUFFIXES) {
            mockMvc.perform(get("/e/no-existe-zzzzzzzz" + suffix))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith("text/html"))
                    .andExpect(content().string(containsString("Evento no encontrado")));
        }
    }

    @Test
    @DisplayName("URLs viejas /menu.html?slug=X -> 302 (no 301) a /e/X, con el subpath de cada página")
    void legacyUrlsRedirectWith302() throws Exception {
        mockMvc.perform(get("/menu.html").param("slug", SLUG)).andExpect(status().isFound()).andExpect(redirectedUrl("/e/" + SLUG));
        mockMvc.perform(get("/upload.html").param("slug", SLUG)).andExpect(status().isFound()).andExpect(redirectedUrl("/e/" + SLUG + "/subir"));
        mockMvc.perform(get("/album.html").param("slug", SLUG)).andExpect(status().isFound()).andExpect(redirectedUrl("/e/" + SLUG + "/album"));
        mockMvc.perform(get("/messages.html").param("slug", SLUG)).andExpect(status().isFound()).andExpect(redirectedUrl("/e/" + SLUG + "/mensajes"));
        mockMvc.perform(get("/screen.html").param("slug", SLUG)).andExpect(status().isFound()).andExpect(redirectedUrl("/e/" + SLUG + "/pantalla"));
    }

    @Test
    @DisplayName("URL vieja sin slug, o con un slug que no puede ser válido, no redirige a nada ajeno: vuelve a /")
    void legacyUrlsWithoutValidSlugGoHome() throws Exception {
        mockMvc.perform(get("/menu.html")).andExpect(status().isFound()).andExpect(redirectedUrl("/"));
        mockMvc.perform(get("/menu.html").param("slug", "https://evil.example/x")).andExpect(status().isFound()).andExpect(redirectedUrl("/"));
        mockMvc.perform(get("/menu.html").param("slug", "../admin")).andExpect(status().isFound()).andExpect(redirectedUrl("/"));
    }

    @Test
    @DisplayName("/ ya no redirige a una boda: muestra una pantalla neutra con 200")
    void rootIsNeutral() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Escaneá el código QR")));
    }

    @Test
    @DisplayName("Referrer-Policy: strict-origin-when-cross-origin en páginas de invitado, API, estáticos y login")
    void referrerPolicyOnEveryResponse() throws Exception {
        String expected = "strict-origin-when-cross-origin";
        mockMvc.perform(get("/e/" + SLUG)).andExpect(header().string("Referrer-Policy", expected));
        mockMvc.perform(get("/e/no-existe-zzzzzzzz")).andExpect(header().string("Referrer-Policy", expected));
        mockMvc.perform(get("/api/v1/events/" + SLUG)).andExpect(header().string("Referrer-Policy", expected));
        mockMvc.perform(get("/css/theme.css")).andExpect(header().string("Referrer-Policy", expected));
        mockMvc.perform(get("/admin/login")).andExpect(header().string("Referrer-Policy", expected));
        mockMvc.perform(get("/api/v1/admin/events/" + SLUG + "/photos")).andExpect(header().string("Referrer-Policy", expected));
    }

    @Test
    @DisplayName("El QR del evento apunta a APP_BASE_URL + /e/{slug}")
    void guestUrlFormatIsStable() {
        assertThat(new com.tuapp.eventfoto.common.config.AppUrls("https://eventfoto.com.ar/", "local").guestMenuUrl(SLUG))
                .isEqualTo("https://eventfoto.com.ar/e/" + SLUG);
    }
}
