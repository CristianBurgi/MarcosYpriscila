package com.tuapp.eventfoto.photo;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuapp.eventfoto.common.config.JwtTokenProvider;
import com.tuapp.eventfoto.common.exception.UploadInProgressException;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.photo.dto.ConfirmUploadRequestDTO;
import com.tuapp.eventfoto.storage.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 9.1 Bloque 4: la upload_key de /confirm pertenece al evento de la request. Tres capas, cada
 * una con su test (y su rojo por separado):
 *   1. /confirm valida el prefijo events/{eventId}/ ANTES de reclamar o tocar la base;
 *   2. la rama idempotente busca la foto acotada al evento (findByUploadKeyAndEventId);
 *   3. deletePhoto no borra un objeto de storage que esté fuera de events/{eventId}/.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConfirmKeyOwnershipTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EventRepository eventRepository;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private PhotoRepository photoRepository;
    @Autowired private PhotoUploadClaimRepository claimRepository;
    @Autowired private GuestQuotaRepository guestQuotaRepository;
    @Autowired private PhotoService photoService;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @MockBean private StorageService storageService;

    private Organizer organizerA;
    private Event eventA;
    private Event eventB;
    private String keyOfB;

    @BeforeEach
    void setUp() {
        cleanUp();
        organizerA = organizerRepository.save(Organizer.builder().email("own-a@test.com").build());
        Organizer organizerB = organizerRepository.save(Organizer.builder().email("own-b@test.com").build());
        eventA = event(organizerA, "evento-key-a-k7m2xq9p");
        eventB = event(organizerB, "evento-key-b-x4h8wt2n");
        keyOfB = key(eventB);
        when(storageService.streamObject(anyString())).thenAnswer(inv -> new ByteArrayInputStream(JPEG));
        when(storageService.generatePublicUrl(any())).thenReturn("https://r2.test/foto.jpg");
    }

    @AfterEach
    void cleanUp() {
        photoRepository.deleteAll();
        claimRepository.deleteAll();
        guestQuotaRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
    }

    private Event event(Organizer organizer, String slug) {
        return eventRepository.save(Event.builder()
                .organizer(organizer).name("Evento " + slug).slug(slug)
                .eventDate(LocalDate.now().plusDays(1)).uploadDeadline(Instant.now().plusSeconds(864000))
                .isActive(true).origin(EventOrigin.PAID).build());
    }

    private static String key(Event event) {
        return "events/" + event.getId() + "/" + UUID.randomUUID() + ".jpg";
    }

    private org.springframework.test.web.servlet.ResultActions confirm(Event event, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/events/" + event.getSlug() + "/photos/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ConfirmUploadRequestDTO(key, "Ana", null, "token-confirm-aaaa"))));
    }

    // ---------- Capa 1: prefijo ----------

    @Test
    @DisplayName("Capa 1: /confirm en A con una key de B -> 400, sin reclamación, sin foto, sin tocar storage")
    void foreignKeyIsRejectedBeforeAnyWork() throws Exception {
        confirm(eventA, keyOfB).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La clave de la subida no es válida para este evento."));

        assertThat(claimRepository.count()).as("no se debe crear ninguna reclamación").isZero();
        assertThat(photoRepository.count()).isZero();
        verify(storageService, never()).streamObject(anyString());
    }

    @Test
    @DisplayName("Capa 1: la respuesta es idéntica exista o no la key en B (no hay forma de sondear el evento ajeno)")
    void responseIsTheSameWhetherOrNotTheKeyExistsInB() throws Exception {
        // La key de B existe de verdad: foto de B reclamada y persistida.
        claimRepository.save(PhotoUploadClaim.builder().uploadKey(keyOfB).claimedAt(Instant.now()).build());
        photoRepository.save(Photo.builder().event(eventB).storageKey(keyOfB).uploadKey(keyOfB).uploaderName("Beto").build());

        String existing = confirm(eventA, keyOfB).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        String unknown = confirm(eventA, key(eventB)).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

        assertThat(normalize(existing)).isEqualTo(normalize(unknown));
        assertThat(existing).doesNotContain("Beto").doesNotContain(eventB.getId().toString());
    }

    @Test
    @DisplayName("Capa 1: keys mal formadas (formato viejo, '..', mayúsculas, extensión rara, de otro evento) -> 400")
    void malformedKeysAreRejected() throws Exception {
        String uuid = UUID.randomUUID().toString();
        for (String bad : new String[]{
                "photos/" + eventA.getSlug() + "/" + uuid + ".jpg",
                "events/" + eventA.getId() + "/../" + eventB.getId() + "/" + uuid + ".jpg",
                "events/" + eventA.getId() + "/" + uuid.toUpperCase() + ".jpg",
                "events/" + eventA.getId() + "/" + uuid + ".php",
                "events/" + eventA.getId() + "/" + uuid,
                "events/" + eventA.getId() + "/" + uuid + ".jpg/extra",
                "/events/" + eventA.getId() + "/" + uuid + ".jpg"}) {
            confirm(eventA, bad).andExpect(status().isBadRequest());
        }
        assertThat(claimRepository.count()).isZero();
    }

    @Test
    @DisplayName("La key propia funciona (201) y el reintento con la misma key devuelve la MISMA foto (idempotencia del Bloque C)")
    void ownKeyWorksAndRetryIsIdempotent() throws Exception {
        String ownKey = key(eventA);
        String first = confirm(eventA, ownKey).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = confirm(eventA, ownKey).andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(second).get("id").asText()).isEqualTo(objectMapper.readTree(first).get("id").asText());
        assertThat(photoRepository.count()).isEqualTo(1);
    }

    // ---------- Capa 2: la rama idempotente acotada al evento ----------

    @Test
    @DisplayName("Capa 2: resolver una key ya reclamada desde A NUNCA devuelve la foto de B")
    void alreadyClaimedLookupIsScopedToTheEvent() {
        claimRepository.save(PhotoUploadClaim.builder().uploadKey(keyOfB).claimedAt(Instant.now()).build());
        photoRepository.save(Photo.builder().event(eventB).storageKey(keyOfB).uploadKey(keyOfB).uploaderName("Beto").build());

        PhotoServiceImpl target = AopTestUtils.getTargetObject(photoService);

        // Desde B (su propio evento) sí se resuelve la foto...
        assertThat(target.resolveAlreadyClaimedConfirm(eventB.getId(), keyOfB).uploaderName()).isEqualTo("Beto");
        // ...desde A no: la foto no es de A y la reclamación sigue "en proceso".
        assertThatThrownBy(() -> target.resolveAlreadyClaimedConfirm(eventA.getId(), keyOfB))
                .isInstanceOf(UploadInProgressException.class);
    }

    // ---------- Capa 3: la guarda de deletePhoto ----------

    @Test
    @DisplayName("Capa 3: borrar una foto de A cuyo storageKey apunta a un objeto de B NO borra el objeto de B (WARN) y sí la fila")
    void deleteDoesNotTouchObjectsOfAnotherEvent() throws Exception {
        Photo poisoned = photoRepository.save(Photo.builder().event(eventA).storageKey(keyOfB).uploaderName("Intruso").build());

        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger logger = (Logger) LoggerFactory.getLogger(PhotoServiceImpl.class);
        logger.addAppender(logs);
        try {
            mockMvc.perform(delete("/api/v1/admin/events/" + eventA.getSlug() + "/photos/" + poisoned.getId())
                    .header("Authorization", "Bearer " + jwtTokenProvider.generateOrganizerToken(
                            organizerA.getId(), organizerA.getEmail(), organizerA.getTokenVersion())))
                    .andExpect(status().isNoContent());
        } finally {
            logger.detachAppender(logs);
        }

        verify(storageService, never()).deleteFile(keyOfB);
        assertThat(photoRepository.existsById(poisoned.getId())).as("la fila sí se borra").isFalse();
        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains(keyOfB).contains("NO se borra el objeto");
        });
    }

    @Test
    @DisplayName("Control de la capa 3: una foto con su clave propia sí borra su objeto")
    void deleteRemovesTheObjectOfItsOwnEvent() throws Exception {
        String ownKey = key(eventA);
        Photo photo = photoRepository.save(Photo.builder().event(eventA).storageKey(ownKey).uploaderName("Ana").build());

        mockMvc.perform(delete("/api/v1/admin/events/" + eventA.getSlug() + "/photos/" + photo.getId())
                .header("Authorization", "Bearer " + jwtTokenProvider.generateOrganizerToken(
                        organizerA.getId(), organizerA.getEmail(), organizerA.getTokenVersion())))
                .andExpect(status().isNoContent());

        verify(storageService).deleteFile(ownKey);
    }

    private String normalize(String body) {
        return body.replaceAll("\"timestamp\":\"[^\"]*\",?", "");
    }
}
