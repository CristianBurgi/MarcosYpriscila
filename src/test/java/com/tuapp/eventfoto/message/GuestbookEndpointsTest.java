package com.tuapp.eventfoto.message;

import com.tuapp.eventfoto.common.config.JwtAuthenticationFilter;
import com.tuapp.eventfoto.common.config.JwtTokenProvider;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventRepository;
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
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
    private JwtTokenProvider jwtTokenProvider;

    private Event event;

    @BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        photoRepository.deleteAll();
        eventRepository.findBySlug("libro-endpoint").ifPresent(eventRepository::delete);
        event = eventRepository.save(Event.builder()
                .name("Boda de prueba")
                .slug("libro-endpoint")
                .eventDate(Instant.parse("2026-09-19T18:00:00Z"))
                .uploadDeadline(Instant.parse("2026-10-04T23:59:00Z"))
                .isActive(true)
                .build());
        messageRepository.save(Message.builder().event(event).authorName("Tía Marta").text("¡Felicidades! 😘").build());
        photoRepository.save(Photo.builder().event(event).storageKey("photos/libro-endpoint/foto.jpg").uploaderName("Ana").build());
    }

    private Cookie adminCookie() {
        return new Cookie(JwtAuthenticationFilter.COOKIE_NAME, jwtTokenProvider.generateToken("admin@boda.com"));
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
        List<String> full = zipEntries(mockMvc.perform(get("/api/v1/admin/photos/events/libro-endpoint/download-zip").cookie(adminCookie()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"album-libro-endpoint.zip\""))
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(full).first().isEqualTo("libro-de-visitas.pdf");
        assertThat(full).hasSize(2); // PDF + la foto

        String photoId = photoRepository.findAll().get(0).getId().toString();
        List<String> selection = zipEntries(mockMvc.perform(get("/api/v1/admin/photos/download-zip")
                        .param("slug", "libro-endpoint").param("photoIds", photoId).cookie(adminCookie()))
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
