package com.tuapp.eventfoto.photo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.photo.dto.UploadUrlRequestDTO;
import com.tuapp.eventfoto.storage.StorageKeys;
import com.tuapp.eventfoto.storage.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 9.1 Bloque 4: toda foto nueva se guarda bajo events/{eventId}/{uuid}.{ext}, con extensión
 * permitida y coherente con el content-type declarado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UploadKeyFormatTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EventRepository eventRepository;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private PhotoRepository photoRepository;
    @Autowired private GuestQuotaRepository guestQuotaRepository;
    @Autowired private com.tuapp.eventfoto.common.config.RateLimiterService rateLimiterService;
    @MockBean private StorageService storageService;

    private Event event;

    @BeforeEach
    void setUp() {
        cleanUp();
        rateLimiterService.resetRateLimits();
        Organizer organizer = organizerRepository.save(Organizer.builder().email("keys@test.com").build());
        event = eventRepository.save(Event.builder()
                .organizer(organizer).name("Evento Keys").slug("evento-keys-k7m2xq9p")
                .eventDate(LocalDate.now().plusDays(1)).uploadDeadline(Instant.now().plusSeconds(864000))
                .isActive(true).origin(EventOrigin.PAID).build());
        when(storageService.generatePublicUrl(any())).thenReturn("https://r2.test/foto.jpg");
        when(storageService.generateUploadUrl(anyString(), anyString())).thenReturn("https://r2.test/presigned");
    }

    @AfterEach
    void cleanUp() {
        photoRepository.deleteAll();
        guestQuotaRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
    }

    private org.springframework.test.web.servlet.ResultActions direct(String filename, String contentType) throws Exception {
        return mockMvc.perform(multipart("/api/v1/events/" + event.getSlug() + "/photos/upload-direct")
                .file(new MockMultipartFile("file", filename, contentType, JPEG))
                .param("uploaderName", "Ana").param("guestToken", "token-keys-aaaa"));
    }

    @Test
    @DisplayName("upload-url devuelve una key events/{eventId}/{uuid}.ext que el propio /confirm acepta")
    void uploadUrlReturnsEventScopedKey() throws Exception {
        String body = mockMvc.perform(post("/api/v1/events/" + event.getSlug() + "/photos/upload-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UploadUrlRequestDTO("image/jpeg", "IMG_0001.JPG", "token-keys-aaaa"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String key = objectMapper.readTree(body).get("key").asText();
        assertThat(key).startsWith("events/" + event.getId() + "/").endsWith(".jpg");
        assertThat(StorageKeys.belongsToEvent(event.getId(), key)).isTrue();
        verify(storageService).generateUploadUrl(eq(key), eq("image/jpeg"));
    }

    @Test
    @DisplayName("upload-direct guarda bajo events/{eventId}/{uuid}.ext")
    void uploadDirectStoresUnderEventPrefix() throws Exception {
        direct("foto.png", "image/png").andExpect(status().isCreated())
                .andExpect(jsonPath("$.storageKey").value(org.hamcrest.Matchers.startsWith("events/" + event.getId() + "/")))
                .andExpect(jsonPath("$.storageKey").value(org.hamcrest.Matchers.endsWith(".png")));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storageService).uploadBytes(key.capture(), any(), anyString());
        assertThat(StorageKeys.belongsToEvent(event.getId(), key.getValue())).isTrue();
    }

    @Test
    @DisplayName("upload-direct rechaza extensión no permitida (400) y no sube nada")
    void uploadDirectRejectsForbiddenExtension() throws Exception {
        direct("shell.php", "image/jpeg").andExpect(status().isBadRequest());
        direct("foto.gif", "image/gif").andExpect(status().isBadRequest());
        verify(storageService, never()).uploadBytes(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("upload-direct con archivos transcodificados a JPEG por el navegador (foto.png / foto.webp / IMG.HEIC + image/jpeg) -> 201, y la clave queda .jpg")
    void uploadDirectAcceptsFilesTranscodedToJpeg() throws Exception {
        for (String filename : new String[]{"foto.png", "foto.webp", "IMG.HEIC"}) {
            direct(filename, "image/jpeg").andExpect(status().isCreated())
                    .andExpect(jsonPath("$.storageKey").value(org.hamcrest.Matchers.startsWith("events/" + event.getId() + "/")))
                    .andExpect(jsonPath("$.storageKey").value(org.hamcrest.Matchers.endsWith(".jpg")));
        }
    }

    @Test
    @DisplayName("upload-direct sigue rechazando con 400 lo que no es coherente: extensión no permitida, o un tipo declarado que contradice la extensión")
    void uploadDirectStillRejectsWhatIsNotCoherent() throws Exception {
        direct("shell.php", "image/jpeg").andExpect(status().isBadRequest());
        direct("shell.exe", "image/jpeg").andExpect(status().isBadRequest());
        direct("foto.jpg", "image/png").andExpect(status().isBadRequest());   // el nombre dice JPEG y el tipo PNG
        direct("foto.png", "image/webp").andExpect(status().isBadRequest());
        verify(storageService, never()).uploadBytes(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("upload-url con foto.png + image/jpeg (captura grande recomprimida por el cliente) -> 200 con una clave .jpg coherente con el tipo")
    void uploadUrlAcceptsTranscodedPng() throws Exception {
        String body = mockMvc.perform(post("/api/v1/events/" + event.getSlug() + "/photos/upload-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UploadUrlRequestDTO("image/jpeg", "foto.png", "token-keys-aaaa"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String key = objectMapper.readTree(body).get("key").asText();
        assertThat(StorageKeys.belongsToEvent(event.getId(), key)).isTrue();
        assertThat(key).endsWith(".jpg");
        verify(storageService).generateUploadUrl(eq(key), eq("image/jpeg"));
    }

    @Test
    @DisplayName("upload-url rechaza con 400 extensión no permitida y tipo incoherente (foto.jpg declarada image/png)")
    void uploadUrlRejectsIncoherentRequests() throws Exception {
        for (String[] bad : new String[][]{{"shell.php", "image/jpeg"}, {"foto.jpg", "image/png"}}) {
            mockMvc.perform(post("/api/v1/events/" + event.getSlug() + "/photos/upload-url")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new UploadUrlRequestDTO(bad[1], bad[0], "token-keys-aaaa"))))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("upload-direct acepta .heic con image/jpeg: el iPhone transcodifica el HEIC a JPEG en el navegador conservando el nombre")
    void uploadDirectAcceptsTranscodedHeic() throws Exception {
        direct("IMG_1234.HEIC", "image/jpeg").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Extensiones: validatedExtension normaliza mayúsculas, deriva del content-type sin nombre y rechaza lo demás")
    void validatedExtensionRules() {
        assertThat(StorageKeys.validatedExtension("IMG_1.JPG", "image/jpeg")).isEqualTo(".jpg");
        assertThat(StorageKeys.validatedExtension("a.heif", "image/heif")).isEqualTo(".heif");
        assertThat(StorageKeys.validatedExtension(null, "image/webp")).isEqualTo(".webp");
        assertThat(StorageKeys.validatedExtension("foto", null)).isEqualTo(".jpg");
        assertThat(StorageKeys.validatedExtension("a.jpg", "application/octet-stream")).isEqualTo(".jpg");
        org.junit.jupiter.api.Assertions.assertThrows(com.tuapp.eventfoto.common.exception.InvalidFileFormatException.class,
                () -> StorageKeys.validatedExtension("a.exe", "image/jpeg"));
        org.junit.jupiter.api.Assertions.assertThrows(com.tuapp.eventfoto.common.exception.InvalidFileFormatException.class,
                () -> StorageKeys.validatedExtension(null, "application/pdf"));
    }
}
