package com.tuapp.eventfoto.photo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuapp.eventfoto.common.config.RateLimiterService;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.photo.dto.ConfirmUploadRequestDTO;
import com.tuapp.eventfoto.storage.FileSignatureValidator;
import com.tuapp.eventfoto.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Grupo D — cobertura real de la decisión HEIC en POST /confirm.
 *
 * <p>A diferencia de {@code PublicApiIntegrationTest}, que stubea {@code streamObject()}
 * con un arreglo de bytes fijo y por lo tanto nunca ejercita la lógica de decisión, acá
 * el {@link StorageService} mockeado está respaldado por un mapa en memoria:
 * {@code streamObject()} devuelve <b>los bytes reales</b> que se "subieron", así que
 * {@link FileSignatureValidator#isHeicSignature(byte[])} dentro de
 * {@code PhotoServiceImpl.validateAndConvertIfNeeded()} corre sobre el contenido de
 * verdad. La invocación (o no) de {@code convertHeicToJpeg()} se verifica con Mockito:
 * el Test 1 falla si se llama; el Test 2 exige que se llame.
 *
 * <p>Test 1 cubre el bug del Grupo C: el frontend real comprime a JPEG y sube el objeto
 * con extensión {@code .heic}/{@code .png}; el sistema debe reconocerlo por magic bytes
 * y guardarlo tal cual, sin pasar por {@code heif-convert}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HeicConfirmDecisionIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EventRepository eventRepository;
    @Autowired private PhotoRepository photoRepository;
    @Autowired private GuestQuotaRepository guestQuotaRepository;
    @Autowired private RateLimiterService rateLimiterService;

    @MockBean private StorageService storageService;

    /** "Bucket" en memoria: lo que sube uploadBytes() y lo que devuelve streamObject(). */
    private final Map<String, byte[]> bucket = new ConcurrentHashMap<>();

    // Header JFIF idéntico al que registran los logs de producción del Grupo C:
    // FF D8 FF E0 00 10 "JFIF" 00 01 ...
    private static final byte[] REAL_JPEG_BYTES = {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
            'J', 'F', 'I', 'F', 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00
    };

    // Caja ISOBMFF "ftyp" con major brand "heic" -- exactamente los bytes que inspecciona
    // isHeicSignature(). Suficiente para ejercitar el camino de decisión + reescritura de
    // key; la conversión libheif real (binario heif-convert) queda fuera de CI y se
    // verifica a mano con un HEIC de iPhone real (ver memoria Fase 8).
    private static byte[] realHeicHeader() {
        byte[] h = new byte[24];
        h[0] = 0x00; h[1] = 0x00; h[2] = 0x00; h[3] = 0x18; // box size = 24
        System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, h, 4, 4);
        System.arraycopy("heic".getBytes(StandardCharsets.US_ASCII), 0, h, 8, 4);
        // minor version (4 bytes en cero) + compatible brands
        System.arraycopy("heic".getBytes(StandardCharsets.US_ASCII), 0, h, 16, 4);
        System.arraycopy("mif1".getBytes(StandardCharsets.US_ASCII), 0, h, 20, 4);
        return h;
    }

    @BeforeEach
    void setUp() {
        rateLimiterService.resetRateLimits();
        photoRepository.deleteAll();
        guestQuotaRepository.deleteAll();
        eventRepository.deleteAll();
        bucket.clear();

        eventRepository.save(Event.builder()
                .name("Boda de Marcos y Priscila")
                .slug("marcos-y-priscila")
                .eventDate(Instant.now().plusSeconds(86_400))
                .uploadDeadline(Instant.now().plusSeconds(864_000))
                .isActive(true)
                .build());

        // StorageService respaldado por el mapa -> la decisión corre sobre bytes reales.
        when(storageService.generatePublicUrl(anyString()))
                .thenAnswer(inv -> "https://fake-r2/" + inv.getArgument(0, String.class));
        when(storageService.streamObject(anyString()))
                .thenAnswer(inv -> {
                    byte[] b = bucket.get(inv.getArgument(0, String.class));
                    if (b == null) throw new IllegalStateException("objeto inexistente: " + inv.getArgument(0));
                    return new ByteArrayInputStream(b);
                });
        doAnswer(inv -> {
            bucket.put(inv.getArgument(0, String.class), ((byte[]) inv.getArgument(1)).clone());
            return null;
        }).when(storageService).uploadBytes(anyString(), any(), anyString());
        doAnswer(inv -> {
            bucket.remove(inv.getArgument(0, String.class));
            return null;
        }).when(storageService).deleteFile(anyString());
        // Conversión fake: devuelve un JPEG mínimo válido. El Test 1 verifica que NO se invoque.
        when(storageService.convertHeicToJpeg(any())).thenReturn(REAL_JPEG_BYTES);
    }

    @ParameterizedTest(name = "key con extensión {0}")
    @ValueSource(strings = {".heic", ".png"})
    @DisplayName("Test 1 (Grupo C): JPEG real con extensión engañosa -> detectado por magic bytes, NO se convierte, se guarda tal cual")
    void jpegRealConExtensionEnganosa_noPasaPorHeifConvert(String extension) throws Exception {
        // Predicado que usa PhotoServiceImpl: sobre estos bytes debe dar false.
        assertThat(FileSignatureValidator.isHeicSignature(REAL_JPEG_BYTES)).isFalse();
        assertThat(FileSignatureValidator.isValidImageSignature(REAL_JPEG_BYTES)).isTrue();

        String key = "photos/marcos-y-priscila/" + UUID.randomUUID() + extension;
        bucket.put(key, REAL_JPEG_BYTES); // simula la subida del cliente vía presigned URL

        ConfirmUploadRequestDTO request =
                new ConfirmUploadRequestDTO(key, "emi", null, "guest-token-real-jpeg" + extension);

        mockMvc.perform(post("/api/v1/events/marcos-y-priscila/photos/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isApproved", is(false)))
                .andExpect(jsonPath("$.uploaderName", is("emi")))
                .andExpect(jsonPath("$.storageKey", is(key))); // key intacta => no hubo conversión

        verify(storageService, never()).convertHeicToJpeg(any());
        verify(storageService, never()).uploadBytes(anyString(), any(), anyString());
        assertThat(bucket).containsOnlyKeys(key);
        assertThat(bucket.get(key)).isEqualTo(REAL_JPEG_BYTES); // bytes sin tocar
        assertThat(photoRepository.findAll())
                .singleElement()
                .satisfies(p -> assertThat(p.getStorageKey()).isEqualTo(key));
    }

    @Test
    @DisplayName("Test 2 (camino defensivo): firma HEIC real -> se invoca heif-convert y la foto final queda como JPEG en key .jpg")
    void contenidoConFirmaHeic_seConvierteAJpeg() throws Exception {
        byte[] heicBytes = realHeicHeader();
        assertThat(FileSignatureValidator.isHeicSignature(heicBytes)).isTrue();

        String heicKey = "photos/marcos-y-priscila/" + UUID.randomUUID() + ".heic";
        bucket.put(heicKey, heicBytes);

        ConfirmUploadRequestDTO request =
                new ConfirmUploadRequestDTO(heicKey, "emi", null, "guest-token-real-heic");

        String body = mockMvc.perform(post("/api/v1/events/marcos-y-priscila/photos/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storageKey", org.hamcrest.Matchers.endsWith(".jpg")))
                .andExpect(jsonPath("$.storageKey", not(is(heicKey))))
                .andReturn().getResponse().getContentAsString();

        verify(storageService, times(1)).convertHeicToJpeg(any());

        String finalKey = objectMapper.readTree(body).get("storageKey").asText();
        assertThat(bucket).doesNotContainKey(heicKey);          // el HEIC original se borró
        assertThat(bucket).containsKey(finalKey);
        assertThat(FileSignatureValidator.isValidImageSignature(bucket.get(finalKey))).isTrue(); // JPEG válido
    }
}
