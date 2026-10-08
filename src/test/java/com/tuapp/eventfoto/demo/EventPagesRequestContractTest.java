package com.tuapp.eventfoto.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.event.UploadWindow;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Fase 9.7-B, criterio duro: un evento real hace EXACTAMENTE las mismas requests que en main.
 * <ol>
 *   <li>Cada página cambió solo en lo documentado: con las sustituciones de abajo, el archivo de main
 *       (golden/guest-pages/raw-*) queda idéntico al de la rama. Ningún otro byte.</li>
 *   <li>event-context.js (corrido en Node) arma EVENT_API, EVENT_BASE y EVENT_STORAGE_PREFIX con los valores que
 *       reemplazan a los literales de main, y no pisa los que ya vienen definidos.</li>
 *   <li>Una página de evento real no lleva nada de la demo; en una de la demo el script inyectado va antes de
 *       event-context.js.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EventPagesRequestContractTest {

    private static final String[] PAGES = {"menu", "upload", "album", "messages", "screen"};
    private static final String SLUG = "contrato-k7m2xq9p";

    @Autowired private MockMvc mockMvc;
    @Autowired private EventRepository eventRepository;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private DemoSessionRepository demoSessions;

    @BeforeEach
    void setUp() {
        cleanUp();
        Organizer organizer = organizerRepository.save(Organizer.builder().email("contrato@test.com").build());
        eventRepository.save(Event.builder().organizer(organizer).name("Contrato").slug(SLUG)
                .eventDate(LocalDate.now(UploadWindow.ZONE)).isActive(true).origin(EventOrigin.PAID).build());
    }

    @AfterEach
    void cleanUp() {
        demoSessions.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
    }

    /** Lo único que cambió en las páginas respecto de main, como sustituciones de texto. */
    static String applyDocumentedChanges(String main) {
        return main
                .replace("fetch(`/api/v1/events/${SLUG}`)", "fetch(EVENT_API)")
                .replace("`/api/v1/events/${SLUG}", "`${EVENT_API}")
                .replace("const GUEST_TOKEN_KEY = 'eventfoto_guest_token';",
                        "const GUEST_TOKEN_KEY = window.EVENT_STORAGE_PREFIX + 'eventfoto_guest_token';")
                .replace("const UPLOADER_NAME_KEY = 'eventfoto_uploader_name';",
                        "const UPLOADER_NAME_KEY = window.EVENT_STORAGE_PREFIX + 'eventfoto_uploader_name';")
                .replaceAll("(?m)^[ \\t]*const SLUG = window\\.EVENT_SLUG;[ \\t]*\\r?\\n", "");
    }

    static final String LIVE_ALBUM_LINK = "<a href=\"#\" data-event-path=\"/album\" id=\"btn-live-album\" class=\"btn-elegant\" "
            + "style=\"display: none; margin-top: 1.2rem;\"><i class=\"fa-solid fa-images\"></i> Ver el álbum en vivo</a>";
    static final String UPLOAD_SUCCESS = "¡Foto subida con éxito! Ya está en el álbum y en la pantalla del salón. 📸</div>';\n";
    static final String VISIBILITY = "            document.addEventListener('visibilitychange', () => { if (!document.hidden) pollGallery(); });\n";

    /**
     * Fase 9.8: lo único que cambió después de la 9.7-B, sobre el resultado de {@link #applyDocumentedChanges} (texto en LF).
     * <ul>
     *   <li>upload.html: el link "Ver el álbum en vivo" (oculto) debajo de #status-message y la línea que lo muestra al
     *       terminar una subida. Ninguna request nueva salvo que el invitado toque el link (navega al álbum).</li>
     *   <li>album.html: la suscripción al stream (GET {EVENT_API}/stream, el mismo de la pantalla). Cada aviso llama al
     *       mismo pollGallery() de siempre: las requests de fotos son las de main, solo que antes.</li>
     *   <li>screen.html: el texto de la tarjeta del QR. Ninguna request.</li>
     * </ul>
     */
    static String applyFase98Changes(String page, String after97b) {
        return switch (page) {
            case "upload" -> after97b
                    .replace("        <div id=\"status-message\"></div>\n",
                            "        <div id=\"status-message\"></div>\n        " + LIVE_ALBUM_LINK + "\n")
                    .replace(UPLOAD_SUCCESS, UPLOAD_SUCCESS + "            document.getElementById('btn-live-album').style.display = '';\n");
            case "album" -> after97b.replace(VISIBILITY, VISIBILITY
                    + "            // En vivo: el mismo stream que la pantalla avisa altas y bajas y se sincroniza al toque; el polling queda de respaldo.\n"
                    + "            if (window.EventSource) {\n"
                    + "                const stream = new EventSource(`${EVENT_API}/stream`);\n"
                    + "                stream.addEventListener('PHOTO_PUBLISHED', () => pollGallery());\n"
                    + "                stream.addEventListener('PHOTO_DELETED', () => pollGallery());\n"
                    + "            }\n");
            case "screen" -> after97b.replace(
                    "            <div class=\"qr-title\"><i class=\"fa-solid fa-qrcode\" style=\"color: #e5cfb3; font-size: 0.95rem; margin-right: 4px;\"></i> ESCANEÁ EL QR</div>\n"
                            + "            <div class=\"qr-subtitle\">¡y subí tu foto en vivo!</div>\n",
                    "            <div class=\"qr-title\">Sacá una foto y aparecé en la pantalla 📸</div>\n"
                            + "            <div class=\"qr-subtitle\">Escaneá el código con la cámara de tu celular</div>\n");
            default -> after97b;
        };
    }

    private static String lf(String s) {
        return s.replace("\r\n", "\n");
    }

    @Test
    @DisplayName("Las 5 páginas difieren de main solo en EVENT_API, las claves con EVENT_STORAGE_PREFIX, el SLUG que ya no se usa y lo de la 9.8")
    void pagesChangedOnlyAsDocumented() throws IOException {
        for (String page : PAGES) {
            String main = Files.readString(Path.of("src/test/resources/golden/guest-pages/raw-" + page + ".html"), StandardCharsets.UTF_8);
            String branch = new ClassPathResource("guest-pages/" + page + ".html").getContentAsString(StandardCharsets.UTF_8);
            String after97b = lf(applyDocumentedChanges(main));
            String expected = applyFase98Changes(page, after97b);
            if (List.of("upload", "album", "screen").contains(page)) {
                assertThat(expected).as(page + ": el cambio de la 9.8 encontró su lugar en main").isNotEqualTo(after97b);
            }
            assertThat(lf(branch)).as(page).isEqualTo(expected);
            assertThat(branch).as(page).doesNotContain("/api/v1/events/${", "/e/${", "const SLUG", "${SLUG}");
        }
    }

    @Test
    @DisplayName("event-context.js: en un evento real arma los mismos valores que los literales de main; no pisa lo definido")
    void eventContextDefaultsAndDoesNotOverride() throws Exception {
        String js = new ClassPathResource("static/js/event-context.js").getContentAsString(StandardCharsets.UTF_8);
        String harness = """
                const js = process.argv[process.argv.length - 1];
                function run(path, preset) {
                  const window = Object.assign({ location: { pathname: path } }, preset);
                  const document = { addEventListener() {} };
                  new Function('window', 'document', js)(window, document);
                  return { api: window.EVENT_API, base: window.EVENT_BASE, prefix: window.EVENT_STORAGE_PREFIX, slug: window.EVENT_SLUG };
                }
                console.log(JSON.stringify({
                  real: run('/e/%s/subir', {}),
                  demo: run('/demo/abc/subir', { EVENT_API: '/api/v1/demo/abc', EVENT_BASE: '/demo/abc', EVENT_STORAGE_PREFIX: 'demo:abc:' })
                }));
                """.formatted(SLUG);
        JsonNode out = runNode(harness, js);
        assertThat(out.at("/real/api").asText()).isEqualTo("/api/v1/events/" + SLUG);
        assertThat(out.at("/real/base").asText()).isEqualTo("/e/" + SLUG);
        assertThat(out.at("/real/prefix").asText()).isEmpty(); // claves de localStorage iguales a las de main
        assertThat(out.at("/demo/api").asText()).isEqualTo("/api/v1/demo/abc");
        assertThat(out.at("/demo/base").asText()).isEqualTo("/demo/abc");
        assertThat(out.at("/demo/prefix").asText()).isEqualTo("demo:abc:");
        assertThat(out.at("/demo/slug").asText()).isEmpty(); // en la demo SLUG queda vacío y nadie lo usa
    }

    @Test
    @DisplayName("Un evento real no lleva nada de la demo; en la demo el script inyectado va antes de event-context.js")
    void injectionOnlyInDemoAndBeforeEventContext() throws Exception {
        for (String suffix : new String[]{"", "/subir", "/album", "/mensajes", "/pantalla"}) {
            String html = mockMvc.perform(get("/e/" + SLUG + suffix)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(html).as("real " + suffix).doesNotContain("window.EVENT_API", "demo.js", "demo.css", "/api/v1/demo");
        }

        String sid = DemoService.newSid();
        demoSessions.save(DemoSession.builder().sid(sid).color("#7b2d8e").eventDate(LocalDate.now()).createdAt(Instant.now()).build());
        for (String suffix : new String[]{"", "/subir", "/album", "/mensajes", "/pantalla"}) {
            String html = mockMvc.perform(get("/demo/" + sid + suffix)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            int injected = html.indexOf("window.EVENT_API=\"/api/v1/demo/" + sid + "\"");
            int context = html.indexOf(DemoPageController.CONTEXT_SCRIPT);
            assertThat(injected).as("demo " + suffix + ": script inyectado").isPositive();
            assertThat(context).as("demo " + suffix + ": event-context.js").isGreaterThan(injected);
            assertThat(html).as("demo " + suffix).contains("window.EVENT_STORAGE_PREFIX=\"demo:" + sid + ":\"");
        }
    }

    private static JsonNode runNode(String script, String... args) throws Exception {
        Process process;
        try {
            List<String> command = new java.util.ArrayList<>(List.of("node", "-e", script, "--"));
            command.addAll(List.of(args));
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            if (System.getenv("CI") != null) {
                fail("Node no está disponible en CI: " + e.getMessage());
            }
            assumeTrue(false, "Node no está instalado: se omite la prueba de event-context.js");
            return null;
        }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Node no terminó en 30 s");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as("salida de Node: " + output).isZero();
        return new ObjectMapper().readTree(output);
    }
}
