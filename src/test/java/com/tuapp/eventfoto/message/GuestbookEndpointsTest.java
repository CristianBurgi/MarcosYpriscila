package com.tuapp.eventfoto.message;

import com.tuapp.eventfoto.common.config.JwtAuthenticationFilter;
import com.tuapp.eventfoto.common.config.JwtTokenProvider;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.photo.Photo;
import com.tuapp.eventfoto.photo.PhotoRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Fase 9.0 - Bloque D: descarga del libro de visitas (solo admin) y su inclusión en el
 * ZIP del álbum completo.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class GuestbookEndpointsTest {

    private static final String PDF_URL = "/api/v1/admin/events/libro-endpoint/libro-de-visitas.pdf";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private PhotoRepository photoRepository;
    @Autowired
    private OrganizerRepository organizerRepository;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private Event event;
    private Organizer organizer;

    @BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        photoRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
        organizer = organizerRepository.save(Organizer.builder().email("libro-endpoint@test.com").build());
        event = eventRepository.save(Event.builder()
                .organizer(organizer)
                .name("Boda de prueba")
                .slug("libro-endpoint")
                .eventDate(LocalDate.parse("2026-09-19"))
                .retentionOverrideUntil(LocalDate.parse("2999-01-01")) // fecha fija en el pasado: que el álbum no venza con el calendario
                
                .isActive(true)
                .origin(EventOrigin.PAID).wizardCompletedAt(Instant.now())
                .build());
        messageRepository.save(Message.builder().event(event).authorName("Tía Marta").text("¡Felicidades! 😘").build());
        photoRepository.save(Photo.builder().event(event).storageKey("photos/libro-endpoint/foto.jpg").uploaderName("Ana").build());
    }

    private Cookie adminCookie() {
        return new Cookie(JwtAuthenticationFilter.ORGANIZER_COOKIE_NAME,
                jwtTokenProvider.generateOrganizerToken(organizer.getId(), organizer.getEmail(), organizer.getTokenVersion()));
    }

    @Test
    @DisplayName("GET libro-de-visitas.pdf sin sesión de admin -> 401, sin PDF")
    void downloadRequiresAdmin() throws Exception {
        mockMvc.perform(get(PDF_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Content-Disposition"));
    }

    @Test
    @DisplayName("GET libro-de-visitas.pdf con JWT de admin descarga libro-de-visitas.pdf")
    void adminDownloadsThePdf() throws Exception {
        byte[] body = mockMvc.perform(get(PDF_URL).cookie(adminCookie()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"libro-de-visitas.pdf\""))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(new String(Arrays.copyOf(body, 5), StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("El ZIP del álbum completo trae libro-de-visitas.pdf en la raíz (y primero); una selección de fotos no")
    void fullAlbumZipContainsTheGuestbook() throws Exception {
        List<String> full = zipEntries(mockMvc.perform(get("/api/v1/admin/events/libro-endpoint/photos/download-zip").cookie(adminCookie()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"album-libro-endpoint.zip\""))
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(full).first().isEqualTo("libro-de-visitas.pdf");
        assertThat(full).hasSize(2); // PDF + la foto

        String photoId = photoRepository.findAll().get(0).getId().toString();
        List<String> selection = zipEntries(mockMvc.perform(get("/api/v1/admin/events/libro-endpoint/photos/download-zip")
                        .param("photoIds", photoId).cookie(adminCookie()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(selection).doesNotContain("libro-de-visitas.pdf").hasSize(1);
    }

    private static List<String> zipEntries(byte[] zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }
}
