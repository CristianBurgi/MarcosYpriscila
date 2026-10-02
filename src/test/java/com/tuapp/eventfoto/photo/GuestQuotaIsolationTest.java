package com.tuapp.eventfoto.photo;

import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.storage.StorageService;
import com.tuapp.eventfoto.testsupport.TestEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 9.1 Bloque 4: el mismo guestToken (comparte localStorage entre eventos del mismo dominio)
 * tiene cupos INDEPENDIENTES por evento. Límite bajado a 2 para que el test sea corto.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GuestQuotaIsolationTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};
    private static final String TOKEN = "token-compartido-por-dos-eventos";

    @Autowired private MockMvc mockMvc;
    @Autowired private EventRepository eventRepository;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private PhotoRepository photoRepository;
    @Autowired private GuestQuotaRepository guestQuotaRepository;
    @MockBean private StorageService storageService;

    private Event eventA;
    private Event eventB;

    @BeforeEach
    void setUp() {
        cleanUp();
        Organizer organizer = organizerRepository.save(Organizer.builder().email("quota@test.com").build());
        eventA = event(organizer, "evento-cupo-a-k7m2xq9p");
        eventB = event(organizer, "evento-cupo-b-x4h8wt2n");
        when(storageService.generatePublicUrl(any())).thenReturn("https://r2.test/foto.jpg");
    }

    @AfterEach
    void cleanUp() {
        photoRepository.deleteAll();
        guestQuotaRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
    }

    private Event event(Organizer organizer, String slug) {
        return TestEvents.limited(eventRepository, organizer, slug, 2);
    }

    private org.springframework.test.web.servlet.ResultActions upload(Event event) throws Exception {
        return mockMvc.perform(multipart("/api/v1/events/" + event.getSlug() + "/photos/upload-direct")
                .file(new MockMultipartFile("file", "f.jpg", "image/jpeg", JPEG))
                .param("uploaderName", "Ana").param("guestToken", TOKEN));
    }

    @Test
    @DisplayName("El mismo guestToken agota su cupo en A y sigue con el cupo completo en B")
    void sameTokenHasIndependentQuotaPerEvent() throws Exception {
        upload(eventA).andExpect(status().isCreated());
        upload(eventA).andExpect(status().isCreated());
        upload(eventA).andExpect(status().isForbidden()); // cupo de A agotado

        mockMvc.perform(get("/api/v1/events/" + eventA.getSlug() + "/guest-quota").param("token", TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.remainingPhotos", is(0)));
        mockMvc.perform(get("/api/v1/events/" + eventB.getSlug() + "/guest-quota").param("token", TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.remainingPhotos", is(2)));

        upload(eventB).andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/events/" + eventB.getSlug() + "/guest-quota").param("token", TOKEN))
                .andExpect(jsonPath("$.remainingPhotos", is(1)));
        mockMvc.perform(get("/api/v1/events/" + eventA.getSlug() + "/guest-quota").param("token", TOKEN))
                .andExpect(jsonPath("$.remainingPhotos", is(0)));
    }
}
