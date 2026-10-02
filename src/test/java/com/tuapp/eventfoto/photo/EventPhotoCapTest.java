package com.tuapp.eventfoto.photo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuapp.eventfoto.common.config.RateLimiterService;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.photo.dto.ConfirmUploadRequestDTO;
import com.tuapp.eventfoto.photo.dto.UploadUrlRequestDTO;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 9.5 (tope B): tope de SEGURIDAD de fotos por evento (app.event.max-photos), independiente del límite por
 * invitado. Baja a 3 para que el test sea corto. No es @Transactional: cada request abre sus propias transacciones.
 */
@SpringBootTest(properties = "app.event.max-photos=3")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EventPhotoCapTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};
    private static final String FULL_ALBUM = "El álbum de este evento llegó a su máximo de fotos. Avisale a quien organiza.";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EventRepository eventRepository;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private PhotoRepository photoRepository;
    @Autowired private PhotoUploadClaimRepository claimRepository;
    @Autowired private GuestQuotaRepository guestQuotaRepository;
    @Autowired private RateLimiterService rateLimiterService;
    @MockBean private StorageService storageService;

    private Organizer organizer;
    private int guestSeq;

    @BeforeEach
    void setUp() {
        cleanUp();
        rateLimiterService.resetRateLimits();
        organizer = organizerRepository.save(Organizer.builder().email("tope@test.com").build());
        when(storageService.generatePublicUrl(any())).thenReturn("https://r2.test/foto.jpg");
        when(storageService.generateUploadUrl(anyString(), anyString())).thenReturn("https://r2.test/put");
        when(storageService.streamObject(anyString())).thenAnswer(inv -> new ByteArrayInputStream(JPEG));
    }

    @AfterEach
    void cleanUp() {
        photoRepository.deleteAll();
        claimRepository.deleteAll();
        guestQuotaRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
    }

    private void seedPhotos(Event event, int count) {
        for (int i = 0; i < count; i++) {
            photoRepository.save(Photo.builder().event(event).storageKey("events/" + event.getId() + "/seed-" + i + ".jpg")
                    .uploadKey("seed-" + UUID.randomUUID()).uploaderName("Seed").build());
        }
    }

    private ResultActions direct(Event event) throws Exception {
        return mockMvc.perform(multipart("/api/v1/events/" + event.getSlug() + "/photos/upload-direct")
                .file(new MockMultipartFile("file", "f.jpg", "image/jpeg", JPEG))
                .param("uploaderName", "Ana").param("guestToken", "token-tope-" + (guestSeq++)));
    }

    private ResultActions confirm(Event event, String guestToken) throws Exception {
        String key = "events/" + event.getId() + "/" + UUID.randomUUID() + ".jpg";
        return mockMvc.perform(post("/api/v1/events/" + event.getSlug() + "/photos/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ConfirmUploadRequestDTO(key, "Ana", null, guestToken))));
    }

    @Test
    @DisplayName("Debajo del tope se acepta; en el tope se rechaza con 409 y el mensaje para el invitado")
    void belowTheCapAcceptsAndAtTheCapRejects() throws Exception {
        Event event = TestEvents.limited(eventRepository, organizer, "evento-tope-k7m2xq9p", 24);
        seedPhotos(event, 2);

        direct(event).andExpect(status().isCreated()); // la 3ª: queda justo en el tope
        direct(event).andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(FULL_ALBUM));
        assertThat(photoRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("Aplica también en modo sin límite por invitado")
    void appliesInUnlimitedMode() throws Exception {
        Event event = TestEvents.unlimited(eventRepository, organizer, "evento-tope-ilimitado-x4h8wt2n");
        seedPhotos(event, 3);

        direct(event).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(FULL_ALBUM));
    }

    @Test
    @DisplayName("En el tope, /upload-url se rechaza antes de generar la presigned URL")
    void presignedUrlIsRefusedAtTheCap() throws Exception {
        Event event = TestEvents.limited(eventRepository, organizer, "evento-tope-url-m3n5pq7r", 24);
        seedPhotos(event, 3);

        mockMvc.perform(post("/api/v1/events/" + event.getSlug() + "/photos/upload-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UploadUrlRequestDTO("image/jpeg", "f.jpg", "token-tope-url"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(FULL_ALBUM));
        org.mockito.Mockito.verify(storageService, org.mockito.Mockito.never()).generateUploadUrl(anyString(), anyString());
    }

    @Test
    @DisplayName("/confirm en el tope: 409, el objeto huérfano se borra de storage, sin foto nueva y sin cupo consumido")
    void confirmAtTheCapCleansUpAndDoesNotConsumeQuota() throws Exception {
        Event event = TestEvents.limited(eventRepository, organizer, "evento-tope-confirm-t6v8yz2c", 24);
        seedPhotos(event, 3);

        confirm(event, "token-tope-confirm").andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(FULL_ALBUM));

        verify(storageService).deleteFile(org.mockito.ArgumentMatchers.startsWith("events/" + event.getId()));
        assertThat(photoRepository.count()).isEqualTo(3);
        assertThat(guestQuotaRepository.findByEventIdAndGuestToken(event.getId(), "token-tope-confirm")
                .map(GuestQuota::getPhotosUploaded).orElse(0)).as("no se le cobró cupo al invitado").isZero();
    }

    @Test
    @DisplayName("El tope de un evento no afecta a otro: A lleno, B sigue aceptando")
    void capOfOneEventDoesNotAffectAnother() throws Exception {
        Event a = TestEvents.limited(eventRepository, organizer, "evento-tope-a-k7m2xq9p", 24);
        Event b = TestEvents.limited(eventRepository, organizer, "evento-tope-b-x4h8wt2n", 24);
        seedPhotos(a, 3);

        direct(a).andExpect(status().isConflict());
        direct(b).andExpect(status().isCreated());
        direct(b).andExpect(status().isCreated());
        direct(b).andExpect(status().isCreated());
        direct(b).andExpect(status().isConflict()); // B llega a SU tope: cuenta solo las fotos de B
    }

    @Test
    @DisplayName("Borrar una foto libera lugar: el conteo es de fotos persistidas")
    void deletingAPhotoFreesRoom() throws Exception {
        Event event = TestEvents.unlimited(eventRepository, organizer, "evento-tope-libera-m3n5pq7r");
        seedPhotos(event, 3);
        direct(event).andExpect(status().isConflict());

        photoRepository.delete(photoRepository.findAll().get(0));

        direct(event).andExpect(status().isCreated());
    }
}
