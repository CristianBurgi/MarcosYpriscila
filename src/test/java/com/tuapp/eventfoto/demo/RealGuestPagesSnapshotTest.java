package com.tuapp.eventfoto.demo;

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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Fase 9.7-B: GuestPageController pasó a armar la personalización con EventBrandingHead. Las cinco páginas de un
 * evento real tienen que salir como en main:
 * <ul>
 *   <li>sin personalización, el archivo del classpath tal cual (main hacía lo mismo);</li>
 *   <li>con personalización, el archivo del classpath con EXACTAMENTE el mismo fragmento que inyectaba main, en el
 *       mismo lugar (antes de {@code </head>}) y nada más.</li>
 * </ul>
 * Los archivos de las páginas cambiaron a propósito (EVENT_API, ver EventPagesRequestContractTest): por eso no se
 * compara la página entera contra main, sino el fragmento inyectado, despejado de lo que servía main
 * (golden/guest-pages/served-*) y del archivo crudo de main (golden/guest-pages/raw-*). Los dos se grabaron desde
 * origin/main con este mismo test (-Dgolden.record=true) y {@code git show origin/main:...}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RealGuestPagesSnapshotTest {

    private static final String[][] PAGES = {
            {"", "menu"}, {"/subir", "upload"}, {"/album", "album"}, {"/mensajes", "messages"}, {"/pantalla", "screen"}};
    private static final Path GOLDEN = Path.of("src/test/resources/golden/guest-pages");

    @Autowired private MockMvc mockMvc;
    @Autowired private EventRepository eventRepository;
    @Autowired private OrganizerRepository organizerRepository;

    private Event plain;
    private Event branded;
    private Event colorOnly;

    @BeforeEach
    void setUp() {
        cleanUp();
        Organizer organizer = organizerRepository.save(Organizer.builder().email("snapshot@test.com").build());
        plain = eventRepository.save(event(organizer, "snapshot-sin-marca-k7m2xq9p"));
        branded = event(organizer, "snapshot-con-marca-k7m2xq9p");
        branded.setBackgroundColor("#7b2d8e");
        branded = eventRepository.save(branded);
        branded.setBackgroundImageKey("events/" + branded.getId() + "/branding/" + UUID.fromString("11111111-2222-4333-8444-555555555555") + ".jpg");
        branded = eventRepository.save(branded);
        colorOnly = event(organizer, "snapshot-solo-color-k7m2xq9p");
        colorOnly.setBackgroundColor("#1f6f5c");
        colorOnly = eventRepository.save(colorOnly);
    }

    @AfterEach
    void cleanUp() {
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
    }

    private static Event event(Organizer organizer, String slug) {
        return Event.builder().organizer(organizer).name("Evento " + slug).slug(slug)
                .eventDate(LocalDate.now(UploadWindow.ZONE)).isActive(true).origin(EventOrigin.PAID).build();
    }

    @Test
    @DisplayName("Las 5 páginas de un evento real, sin personalización, con color e imagen y solo con color, inyectan lo mismo que main")
    void realPagesInjectTheSameAsMain() throws Exception {
        boolean record = Boolean.getBoolean("golden.record");
        for (String[] page : PAGES) {
            for (Event event : new Event[]{plain, branded, colorOnly}) {
                String variant = event == plain ? "plain" : event == branded ? "branded" : "color";
                String served = mockMvc.perform(get("/e/" + event.getSlug() + page[0]))
                        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)
                        .replace(event.getId().toString(), "{EVENT_ID}").replace(event.getSlug(), "{SLUG}").replace("\r\n", "\n");
                Path servedGolden = GOLDEN.resolve("served-" + page[1] + "-" + variant + ".html");
                if (record) {
                    Files.createDirectories(GOLDEN);
                    Files.writeString(servedGolden, served, StandardCharsets.UTF_8);
                    continue;
                }
                String mainServed = lf(Files.readString(servedGolden, StandardCharsets.UTF_8));
                String mainRaw = lf(Files.readString(GOLDEN.resolve("raw-" + page[1] + ".html"), StandardCharsets.UTF_8));
                String raw = lf(new ClassPathResource("guest-pages/" + page[1] + ".html").getContentAsString(StandardCharsets.UTF_8));

                String what = page[1] + " " + variant;
                String mainFragment = injected(mainRaw, mainServed, what + " (main)");
                String fragment = injected(raw, served, what + " (rama)");
                assertThat(fragment).as(what).isEqualTo(mainFragment);
                if (event == plain || page[1].equals("screen")) {
                    assertThat(fragment).as(what + ": sin personalización no se inyecta nada").isEmpty();
                } else if (event == branded) {
                    assertThat(fragment).as(what).contains("--brand-bg:url(\"/uploads/events/{EVENT_ID}/branding/");
                } else {
                    assertThat(fragment).as(what).contains("--brand-bg:linear-gradient(165deg,");
                }
            }
        }
    }

    /** Los goldens pueden quedar con CRLF según core.autocrlf: se compara con LF. */
    private static String lf(String s) {
        return s.replace("\r\n", "\n");
    }

    /** Lo que el servidor agregó al archivo: el servido tiene que ser raw[0:head] + fragmento + raw[head:]. */
    private static String injected(String raw, String served, String what) {
        int head = raw.indexOf("</head>");
        String before = raw.substring(0, head);
        String after = raw.substring(head);
        assertThat(served).as(what + ": empieza como el archivo").startsWith(before);
        assertThat(served).as(what + ": termina como el archivo").endsWith(after);
        return served.substring(before.length(), served.length() - after.length());
    }
}
