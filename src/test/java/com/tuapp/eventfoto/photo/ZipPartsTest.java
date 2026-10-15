package com.tuapp.eventfoto.photo;

import com.tuapp.eventfoto.common.config.JwtAuthenticationFilter;
import com.tuapp.eventfoto.common.config.JwtTokenProvider;
import com.tuapp.eventfoto.common.exception.ZipPartChangedException;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.message.GuestbookPdfService;
import com.tuapp.eventfoto.message.Message;
import com.tuapp.eventfoto.message.MessageRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import jakarta.servlet.http.Cookie;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ZIP por partes contra la base real (mismo contexto que GuestbookEndpointsTest). Las partes se arman con
 * ZipPartPlanner.plan(ids, 3) para no cargar cientos de fotos; el panel usa la propiedad real (500).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ZipPartsTest {

    private static final Instant BASE = Instant.parse("2026-09-19T23:00:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private EventRepository eventRepository;
    @Autowired private PhotoRepository photoRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private AlbumReader albumReader;

    private Organizer organizer;
    private Event event;
    private Event otherEvent;

    @BeforeEach
    void setUp() {
        organizer = organizerRepository.save(Organizer.builder().email("zip-partes@test.com").build());
        event = saveEvent("zip-partes-a");
        otherEvent = saveEvent("zip-partes-b");
        messageRepository.save(Message.builder().event(event).authorName("Tía Marta").text("¡Felicidades!").build());
        // 8 fotos; de a pares con la MISMA hora, así el orden también depende del id que desempata.
        for (int i = 0; i < 8; i++) {
            savePhoto(event, BASE.plusSeconds(i / 2), "Invitado" + i);
        }
        for (int i = 0; i < 3; i++) {
            savePhoto(otherEvent, BASE.plusSeconds(i), "Ajeno" + i);
        }
    }

    private Event saveEvent(String slug) {
        return eventRepository.save(Event.builder().organizer(organizer).name("Evento " + slug).slug(slug)
                .eventDate(LocalDate.parse("2026-09-19")).retentionOverrideUntil(LocalDate.parse("2999-01-01"))
                .isActive(true).origin(EventOrigin.PAID).wizardCompletedAt(Instant.now()).build());
    }

    private Photo savePhoto(Event owner, Instant createdAt, String uploader) {
        return photoRepository.save(Photo.builder().event(owner).storageKey("photos/" + owner.getSlug() + "/" + uploader + ".jpg")
                .uploaderName(uploader).createdAt(createdAt).build());
    }

    private Cookie cookie() {
        return new Cookie(JwtAuthenticationFilter.ORGANIZER_COOKIE_NAME,
                jwtTokenProvider.generateOrganizerToken(organizer.getId(), organizer.getEmail(), organizer.getTokenVersion()));
    }

    private List<ZipPartPlanner.ZipPart> planOfThree() {
        return ZipPartPlanner.plan(photoRepository.findIdsInUploadOrder(event.getId()), 3);
    }

    private List<UUID> idsOf(ZipPartPlanner.ZipPart part) {
        return albumReader.loadPart(event.getId(), part.from(), part.to()).stream().map(AlbumReader.ZipPhoto::id).toList();
    }

    @Test
    @DisplayName("Las partes juntas son todas las fotos del evento, en orden de subida, sin repetidas ni faltantes")
    void partsCoverTheAlbumExactlyOnce() {
        List<UUID> all = photoRepository.findIdsInUploadOrder(event.getId());
        List<ZipPartPlanner.ZipPart> parts = planOfThree();
        assertThat(parts).hasSize(3); // 3 + 3 + 2

        List<UUID> joined = new ArrayList<>();
        parts.forEach(part -> joined.addAll(idsOf(part)));
        assertThat(joined).containsExactlyElementsOf(all);
        assertThat(idsOf(parts.get(2))).hasSize(2);
    }

    @Test
    @DisplayName("Borrar una foto del medio entre una parte y otra no deja ninguna afuera")
    void deletingAMiddlePhotoLosesNothing() {
        List<ZipPartPlanner.ZipPart> parts = planOfThree();
        List<UUID> firstPart = idsOf(parts.get(0)); // ya descargada
        UUID middle = idsOf(parts.get(1)).get(1);   // no es foto de corte
        photoRepository.deleteById(middle);
        photoRepository.flush();

        List<UUID> joined = new ArrayList<>(firstPart);
        joined.addAll(idsOf(parts.get(1)));
        joined.addAll(idsOf(parts.get(2)));
        assertThat(joined).doesNotHaveDuplicates()
                .containsExactlyElementsOf(photoRepository.findIdsInUploadOrder(event.getId()));
    }

    @Test
    @DisplayName("Borrar una foto de corte: la parte responde 409 (JSON, no un .zip) y el panel recalcula")
    void deletingACutPhotoIsA409() throws Exception {
        ZipPartPlanner.ZipPart second = planOfThree().get(1);
        photoRepository.deleteById(second.from());
        photoRepository.flush();

        assertThatThrownBy(() -> idsOf(second)).isInstanceOf(ZipPartChangedException.class);
        mockMvc.perform(get("/api/v1/admin/events/" + event.getSlug() + "/photos/download-zip").cookie(cookie())
                        .param("from", second.from().toString()).param("to", second.to().toString())
                        .param("part", "2").param("parts", "3"))
                .andExpect(status().isConflict())
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andExpect(jsonPath("$.error").value("Conflict - Album Changed"));
    }

    @Test
    @DisplayName("Los IDs de corte no pueden traer fotos de otro evento: con un corte ajeno, 409 y nada de ese evento")
    void cutIdsFromAnotherEventAreRejected() throws Exception {
        List<UUID> foreign = photoRepository.findIdsInUploadOrder(otherEvent.getId());
        UUID ownFirst = photoRepository.findIdsInUploadOrder(event.getId()).get(0);

        assertThatThrownBy(() -> albumReader.loadPart(event.getId(), foreign.get(0), null)).isInstanceOf(ZipPartChangedException.class);
        assertThatThrownBy(() -> albumReader.loadPart(event.getId(), ownFirst, foreign.get(2))).isInstanceOf(ZipPartChangedException.class);

        String url = "/api/v1/admin/events/" + event.getSlug() + "/photos/download-zip";
        mockMvc.perform(get(url).cookie(cookie()).param("from", foreign.get(0).toString())).andExpect(status().isConflict());
        mockMvc.perform(get(url).cookie(cookie()).param("from", ownFirst.toString()).param("to", foreign.get(2).toString()))
                .andExpect(status().isConflict());
        // Y una parte válida nunca trae fotos del otro evento aunque compartan la hora.
        assertThat(albumReader.loadPart(event.getId(), ownFirst, null)).extracting(AlbumReader.ZipPhoto::id).doesNotContainAnyElementsOf(foreign);
    }

    @Test
    @DisplayName("El libro de visitas va solo en la parte 1; el nombre del archivo dice la parte")
    void guestbookOnlyInPartOne() throws Exception {
        List<ZipPartPlanner.ZipPart> parts = planOfThree();
        String url = "/api/v1/admin/events/" + event.getSlug() + "/photos/download-zip";

        byte[] first = mockMvc.perform(get(url).cookie(cookie()).param("from", parts.get(0).from().toString())
                        .param("to", parts.get(0).to().toString()).param("part", "1").param("parts", "3"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"album-zip-partes-a-parte-1-de-3.zip\""))
                .andReturn().getResponse().getContentAsByteArray();
        byte[] last = mockMvc.perform(get(url).cookie(cookie()).param("from", parts.get(2).from().toString())
                        .param("part", "3").param("parts", "3"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(zipEntries(first)).first().isEqualTo(GuestbookPdfService.FILENAME);
        assertThat(zipEntries(first).stream().filter(name -> name.contains("Invitado"))).hasSize(3);
        assertThat(zipEntries(last)).doesNotContain(GuestbookPdfService.FILENAME);
        assertThat(zipEntries(last).stream().filter(name -> name.contains("Invitado"))).hasSize(2);
    }

    @Test
    @DisplayName("Panel: hasta 500 fotos el botón único; con 501, 'Parte k de N (fotos a–b)' y el texto explicativo")
    void panelShowsPartsOnlyAboveTheLimit() throws Exception {
        String panel = "/admin/eventos/" + event.getSlug();
        mockMvc.perform(get(panel).cookie(cookie())).andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"btnDownloadFullZip\"")))
                .andExpect(content().string(not(containsString("Para que cada descarga termine"))));

        for (int i = 0; i < 493; i++) { // 8 + 493 = 501
            savePhoto(event, BASE.plusSeconds(100 + i), "Masivo" + i);
        }
        photoRepository.flush();
        String html = mockMvc.perform(get(panel).cookie(cookie())).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"btnDownloadFullZip\""))))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("El álbum tiene 501 fotos. Para que cada descarga termine, va en 2 partes:")
                .contains("Parte 1 de 2 (fotos 1–500)")
                .contains("Parte 2 de 2 (fotos 501–501)");

        // El link real de la última parte (sin foto de corte final) descarga: 1 foto y sin libro de visitas.
        Element lastLink = Jsoup.parse(html).select(".zip-parts a").last();
        byte[] lastPart = mockMvc.perform(get(lastLink.attr("href")).cookie(cookie()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("parte-2-de-2")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(zipEntries(lastPart).stream().filter(name -> name.contains("Masivo"))).hasSize(1);
        assertThat(zipEntries(lastPart)).doesNotContain(GuestbookPdfService.FILENAME);
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
