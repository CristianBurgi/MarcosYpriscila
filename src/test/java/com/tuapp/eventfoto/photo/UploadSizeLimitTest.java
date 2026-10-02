package com.tuapp.eventfoto.photo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.photo.dto.ConfirmUploadRequestDTO;
import com.tuapp.eventfoto.storage.StorageService;
import com.tuapp.eventfoto.testsupport.TestEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.ByteArrayInputStream;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 9.5 (tope A): tope de tamaño por archivo (app.upload.max-file-bytes, 15 MB) en /confirm y /upload-direct.
 * El PUT presignado va directo a R2 y no tiene tope propio, así que /confirm es el único punto donde se puede
 * frenar un objeto enorme: con un HeadObject, ANTES de leer un byte (readAllBytes carga el objeto entero en
 * memoria), antes de reclamar la key y antes de consumir cupo.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UploadSizeLimitTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};
    private static final String TOO_LARGE = "La foto es demasiado pesada. Probá con otra o bajale la calidad.";

    @Value("${app.upload.max-file-bytes}") private long maxFileBytes;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EventRepository eventRepository;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private PhotoRepository photoRepository;
    @Autowired private PhotoUploadClaimRepository claimRepository;
    @Autowired private GuestQuotaRepository guestQuotaRepository;
    @MockBean private StorageService storageService;

    private Event event;

    @BeforeEach
    void setUp() {
        cleanUp();
        Organizer organizer = organizerRepository.save(Organizer.builder().email("tamano@test.com").build());
        event = TestEvents.limited(eventRepository, organizer, "evento-tamano-k7m2xq9p", 24);
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

    private String key() {
        return "events/" + event.getId() + "/" + UUID.randomUUID() + ".jpg";
    }

    private ResultActions confirm(String key) throws Exception {
        return mockMvc.perform(post("/api/v1/events/" + event.getSlug() + "/photos/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ConfirmUploadRequestDTO(key, "Ana", null, "token-tamano-aaaa"))));
    }

    @Test
    @DisplayName("El tope es 15 MB (15.728.640 bytes) y es un único valor configurable")
    void limitIs15MegabytesFromConfiguration() {
        assertThat(maxFileBytes).isEqualTo(15L * 1024 * 1024);
    }

    @Test
    @DisplayName("/confirm con un objeto de 15 MB + 1 byte: 413 con el mensaje, objeto borrado de R2, sin leer sus bytes, sin cupo ni reclamación ni foto")
    void oversizedObjectIsRejectedBeforeReadingItAndWithoutConsumingQuota() throws Exception {
        String key = key();
        when(storageService.objectSize(key)).thenReturn(OptionalLong.of(maxFileBytes + 1));
        // El fake de storage falla fuerte si alguien lee los bytes de un objeto sobredimensionado.
        when(storageService.streamObject(anyString())).thenAnswer(inv -> {
            throw new AssertionError("se leyeron los bytes de un objeto sobredimensionado");
        });

        confirm(key).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.message").value(TOO_LARGE));

        verify(storageService).deleteFile(key);
        verify(storageService, never()).streamObject(anyString());
        assertThat(photoRepository.count()).as("sin foto").isZero();
        assertThat(guestQuotaRepository.count()).as("sin cupo consumido (ni siquiera se crea la fila)").isZero();
        assertThat(claimRepository.count()).as("sin reclamación de la key").isZero();
    }

    @Test
    @DisplayName("/confirm con un objeto de exactamente 15 MB: se acepta")
    void objectAtTheLimitIsAccepted() throws Exception {
        String key = key();
        when(storageService.objectSize(key)).thenReturn(OptionalLong.of(maxFileBytes));
        when(storageService.streamObject(key)).thenAnswer(inv -> new ByteArrayInputStream(JPEG));

        confirm(key).andExpect(status().isCreated());

        verify(storageService, never()).deleteFile(anyString());
        assertThat(photoRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("/upload-direct con un archivo de 15 MB + 1 byte: 413 con el mismo mensaje, sin subir nada a storage ni consumir cupo")
    void oversizedDirectUploadIsRejected() throws Exception {
        byte[] oversized = new byte[(int) maxFileBytes + 1];
        System.arraycopy(JPEG, 0, oversized, 0, JPEG.length);

        mockMvc.perform(multipart("/api/v1/events/" + event.getSlug() + "/photos/upload-direct")
                        .file(new MockMultipartFile("file", "enorme.jpg", "image/jpeg", oversized))
                        .param("uploaderName", "Ana").param("guestToken", "token-tamano-bbbb"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value(TOO_LARGE));

        verify(storageService, never()).uploadBytes(anyString(), any(), anyString());
        assertThat(photoRepository.count()).isZero();
        assertThat(guestQuotaRepository.count()).isZero();
    }
}
