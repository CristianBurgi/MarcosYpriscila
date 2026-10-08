package com.tuapp.eventfoto.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuapp.eventfoto.event.UploadWindow;
import com.tuapp.eventfoto.comment.CommentRepository;
import com.tuapp.eventfoto.comment.dto.CreateCommentRequestDTO;
import com.tuapp.eventfoto.common.config.JwtTokenProvider;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.message.MessageRepository;
import com.tuapp.eventfoto.message.dto.CreateMessageRequestDTO;
import com.tuapp.eventfoto.photo.GuestQuotaRepository;
import com.tuapp.eventfoto.photo.Photo;
import com.tuapp.eventfoto.photo.PhotoRepository;
import com.tuapp.eventfoto.photo.dto.ConfirmUploadRequestDTO;
import com.tuapp.eventfoto.photo.dto.UploadUrlRequestDTO;
import com.tuapp.eventfoto.storage.StorageService;
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

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private PhotoRepository photoRepository;

    @Autowired
    private GuestQuotaRepository guestQuotaRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private com.tuapp.eventfoto.common.config.RateLimiterService rateLimiterService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private OrganizerRepository organizerRepository;

    @MockBean
    private StorageService storageService;

    private Event testEvent;
    private String adminJwtToken;

    @BeforeEach
    void setUp() {
        rateLimiterService.resetRateLimits();
        commentRepository.deleteAll();
        messageRepository.deleteAll();
        photoRepository.deleteAll();
        guestQuotaRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();

        Organizer organizer = organizerRepository.save(Organizer.builder().email("public-api@test.com").build());

        testEvent = Event.builder()
                .organizer(organizer)
                .name("Evento de Prueba")
                .slug("evento-demo-k7m2xq9p")
                .eventDate(LocalDate.now(UploadWindow.ZONE))
                .isActive(true)
                .origin(EventOrigin.PAID).wizardCompletedAt(Instant.now())
                .maxPhotosPerGuest(24) // explícito: sin valor, el evento quedaría "sin límite"
                .build();

        eventRepository.save(testEvent);

        adminJwtToken = jwtTokenProvider.generateOrganizerToken(organizer.getId(), organizer.getEmail(), organizer.getTokenVersion());

        when(storageService.generateUploadUrl(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn("https://r2.test-storage.com/upload-presigned-url");
        when(storageService.generatePublicUrl(org.mockito.ArgumentMatchers.any()))
                .thenReturn("https://r2.test-storage.com/public-photo-url.jpg");
        // Firma JPEG válida (FF D8 FF) para que la verificación de magic bytes en /confirm
        // (PhotoServiceImpl.validateAndConvertIfNeeded) pase con un StorageService mockeado.
        // OJO: acá el stub tapa la lógica de decisión HEIC -- la cobertura real de ese
        // camino (JPEG con extensión engañosa, invocación de heif-convert) vive en
        // HeicConfirmDecisionIntegrationTest, con streamObject() respaldado por bytes reales.
        when(storageService.streamObject(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> new ByteArrayInputStream(
                        new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0}));
    }

    @Test
    @DisplayName("GET /api/v1/events/{slug} - Debe retornar 200 OK con los datos del evento")
    void shouldReturnEventDetails() throws Exception {
        mockMvc.perform(get("/api/v1/events/evento-demo-k7m2xq9p"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Evento de Prueba")))
                .andExpect(jsonPath("$.slug", is("evento-demo-k7m2xq9p")));
    }

    @Test
    @DisplayName("GET /api/v1/events/{slug} - Debe retornar 404 Not Found para un slug inexistente")
    void shouldReturn404ForNonExistentSlug() throws Exception {
        mockMvc.perform(get("/api/v1/events/evento-inexistente"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.message", containsString("No se encontró el evento")));
    }

    @Test
    @DisplayName("POST /api/v1/events/{slug}/photos/upload-url - Debe generar Presigned URL exitosamente")
    void shouldGenerateUploadUrl() throws Exception {
        when(storageService.generateUploadUrl(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("https://r2.test-storage.com/upload-presigned-url");

        UploadUrlRequestDTO request = new UploadUrlRequestDTO("image/jpeg", "boda.jpg", "guest-token-upload-url-test");

        mockMvc.perform(post("/api/v1/events/evento-demo-k7m2xq9p/photos/upload-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadUrl", notNullValue()))
                .andExpect(jsonPath("$.key", notNullValue()));
    }

    @Test
    @DisplayName("GET /favicon.ico - Se sirve sin autenticación (antes: 131 NoResourceFoundException en Sentry)")
    void shouldServeFavicon() throws Exception {
        mockMvc.perform(get("/favicon.ico"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("E2E presigned: upload-url -> /confirm -> la foto queda publicada en la galería sin acción del organizador")
    void shouldPublishConfirmedPhotoWithoutAdminAction() throws Exception {
        String guestToken = "guest-token-confirm-test";
        UploadUrlRequestDTO urlRequest = new UploadUrlRequestDTO("image/jpeg", "boda.jpg", guestToken);
        String urlResponse = mockMvc.perform(post("/api/v1/events/evento-demo-k7m2xq9p/photos/upload-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(urlRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String key = objectMapper.readTree(urlResponse).get("key").asText();

        ConfirmUploadRequestDTO request = new ConfirmUploadRequestDTO(key, "Invitado Feliz", "¡Felicidades!", guestToken);
        String confirmResponse = mockMvc.perform(post("/api/v1/events/evento-demo-k7m2xq9p/photos/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uploaderName", is("Invitado Feliz")))
                .andExpect(jsonPath("$.isApproved").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String photoId = objectMapper.readTree(confirmResponse).get("id").asText();

        // Sin ninguna llamada a /api/v1/admin/**: la galería pública ya la muestra.
        mockMvc.perform(get("/api/v1/events/evento-demo-k7m2xq9p/photos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id", is(photoId)))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    @Test
    @DisplayName("E2E upload-direct: la foto subida por el servidor queda publicada en la galería sin acción del organizador")
    void shouldPublishDirectUploadWithoutAdminAction() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "boda.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1, 1});

        String response = mockMvc.perform(multipart("/api/v1/events/evento-demo-k7m2xq9p/photos/upload-direct")
                        .file(file)
                        .param("uploaderName", "Caro")
                        .param("guestToken", "guest-token-direct-test"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String photoId = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(get("/api/v1/events/evento-demo-k7m2xq9p/photos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id", is(photoId)))
                .andExpect(jsonPath("$.content[0].uploaderName", is("Caro")));
    }

    @Test
    @DisplayName("POST /api/v1/events/{slug}/photos/{photoId}/comments - Debe agregar comentario y responder 201 Created")
    void shouldAddCommentToPhoto() throws Exception {
        Photo photo = photoRepository.save(Photo.builder()
                .event(testEvent)
                .storageKey("photos/sample.jpg")
                .build());

        CreateCommentRequestDTO request = new CreateCommentRequestDTO("Tía Marta", "¡Qué hermosa foto!");

        mockMvc.perform(post("/api/v1/events/evento-demo-k7m2xq9p/photos/" + photo.getId() + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorName", is("Tía Marta")))
                .andExpect(jsonPath("$.text", is("¡Qué hermosa foto!")));
    }

    @Test
    @DisplayName("POST /api/v1/events/{slug}/photos/{photoId}/comments - Debe rechazar comentario vacío con 400 Bad Request")
    void shouldRejectEmptyComment() throws Exception {
        Photo photo = photoRepository.save(Photo.builder()
                .event(testEvent)
                .storageKey("photos/sample.jpg")
                .build());

        CreateCommentRequestDTO request = new CreateCommentRequestDTO("", "");

        mockMvc.perform(post("/api/v1/events/evento-demo-k7m2xq9p/photos/" + photo.getId() + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }

    @Test
    @DisplayName("POST /api/v1/events/{slug}/messages - Debe registrar mensaje del libro de visitas")
    void shouldAddGuestbookMessage() throws Exception {
        CreateMessageRequestDTO request = new CreateMessageRequestDTO("Padrino Juan", "Les deseamos toda la felicidad del mundo en esta etapa.");

        mockMvc.perform(post("/api/v1/events/evento-demo-k7m2xq9p/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorName", is("Padrino Juan")))
                .andExpect(jsonPath("$.text", containsString("toda la felicidad")));
    }

    @Test
    @DisplayName("DELETE /api/v1/admin/events/{slug}/comments/{commentId} - Debe eliminar comentario por moderación de admin")
    void shouldDeleteCommentByAdmin() throws Exception {
        Photo photo = photoRepository.save(Photo.builder()
                .event(testEvent)
                .storageKey("photos/sample.jpg")
                .build());

        com.tuapp.eventfoto.comment.Comment comment = commentRepository.save(com.tuapp.eventfoto.comment.Comment.builder()
                .photo(photo)
                .authorName("Inapropiado")
                .text("Texto no permitido")
                .build());

        mockMvc.perform(delete("/api/v1/admin/events/evento-demo-k7m2xq9p/comments/" + comment.getId())
                        .header("Authorization", "Bearer " + adminJwtToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("POST /api/v1/events/{slug}/messages - Debe rechazar mensaje ofensivo con 422 Unprocessable Entity")
    void shouldRejectProfaneMessageWith422() throws Exception {
        CreateMessageRequestDTO request = new CreateMessageRequestDTO("Spammer", "Sos un h.d.p.");

        mockMvc.perform(post("/api/v1/events/evento-demo-k7m2xq9p/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status", is(422)))
                .andExpect(jsonPath("$.message", containsString("Tu mensaje no pudo publicarse")));
    }

    @Test
    @DisplayName("POST /api/v1/events/{slug}/photos/{photoId}/comments - Debe rechazar comentario ofensivo con 422 Unprocessable Entity")
    void shouldRejectProfaneCommentWith422() throws Exception {
        Photo photo = photoRepository.save(Photo.builder()
                .event(testEvent)
                .storageKey("photos/sample.jpg")
                .build());

        CreateCommentRequestDTO request = new CreateCommentRequestDTO("Troll", "Sos una mierda");

        mockMvc.perform(post("/api/v1/events/evento-demo-k7m2xq9p/photos/" + photo.getId() + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status", is(422)))
                .andExpect(jsonPath("$.message", containsString("Tu comentario no pudo publicarse")));
    }

    @Test
    @DisplayName("GET /api/v1/events/{slug}/guest-quota - Debe devolver 24 fotos disponibles para un token nuevo")
    void shouldReturnFullQuotaForNewGuestToken() throws Exception {
        mockMvc.perform(get("/api/v1/events/evento-demo-k7m2xq9p/guest-quota")
                        .param("token", "guest-token-quota-fresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingPhotos", is(24)))
                .andExpect(jsonPath("$.maxPhotosPerGuest", is(24)))
                .andExpect(jsonPath("$.unlimited", is(false)));
    }

    @Test
    @DisplayName("GET /api/v1/events/{slug}/guest-quota - Debe decrecer tras confirmar una subida")
    void shouldDecrementQuotaAfterConfirm() throws Exception {
        String guestToken = "guest-token-quota-decrement";
        ConfirmUploadRequestDTO request = new ConfirmUploadRequestDTO(key(), "Invitado", null, guestToken);

        mockMvc.perform(post("/api/v1/events/evento-demo-k7m2xq9p/photos/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/events/evento-demo-k7m2xq9p/guest-quota")
                        .param("token", guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingPhotos", is(23)));
    }

    @Test
    @DisplayName("POST /api/v1/events/{slug}/photos/confirm - Debe rechazar con 403 al superar el cupo de 24 fotos por invitado")
    void shouldRejectConfirmWithForbiddenWhenGuestQuotaExceeded() throws Exception {
        String guestToken = "guest-token-quota-limit";

        for (int i = 1; i <= 24; i++) {
            ConfirmUploadRequestDTO request = new ConfirmUploadRequestDTO(key(), "Invitado", null, guestToken);
            mockMvc.perform(post("/api/v1/events/evento-demo-k7m2xq9p/photos/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        ConfirmUploadRequestDTO request25 = new ConfirmUploadRequestDTO(key(), "Invitado", null, guestToken);
        mockMvc.perform(post("/api/v1/events/evento-demo-k7m2xq9p/photos/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request25)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.message", containsString("Ya usaste tus 24 fotos")));

        mockMvc.perform(get("/api/v1/events/evento-demo-k7m2xq9p/guest-quota")
                        .param("token", guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingPhotos", is(0)));
    }

    @Test
    @DisplayName("POST /api/v1/events/{slug}/photos/upload-url - Debe rechazar con 403 si el invitado ya agotó su cupo")
    void shouldRejectUploadUrlWithForbiddenWhenGuestQuotaAlreadyExceeded() throws Exception {
        String guestToken = "guest-token-quota-upload-url";

        for (int i = 1; i <= 24; i++) {
            ConfirmUploadRequestDTO request = new ConfirmUploadRequestDTO(key(), "Invitado", null, guestToken);
            mockMvc.perform(post("/api/v1/events/evento-demo-k7m2xq9p/photos/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        UploadUrlRequestDTO uploadUrlRequest = new UploadUrlRequestDTO("image/jpeg", "otra-mas.jpg", guestToken);
        mockMvc.perform(post("/api/v1/events/evento-demo-k7m2xq9p/photos/upload-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(uploadUrlRequest)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.message", containsString("Ya usaste tus 24 fotos")));
    }

    @Test
    @DisplayName("DELETE /api/v1/admin/events/{slug}/messages/{messageId} - Debe eliminar mensaje del libro de visitas por admin")
    void shouldDeleteMessageByAdmin() throws Exception {
        com.tuapp.eventfoto.message.Message message = messageRepository.save(com.tuapp.eventfoto.message.Message.builder()
                .event(testEvent)
                .authorName("Spammer")
                .text("Mensaje indeseado")
                .build());

        mockMvc.perform(delete("/api/v1/admin/events/evento-demo-k7m2xq9p/messages/" + message.getId())
                        .header("Authorization", "Bearer " + adminJwtToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("GET fotos (pública y admin) y mensajes: size se acota a [1, 100]; size=0 no da 500")
    void pageSizeIsClampedTo100() throws Exception {
        for (int i = 0; i < 101; i++) {
            photoRepository.save(Photo.builder().event(testEvent).storageKey("photos/p" + i + ".jpg").build());
            messageRepository.save(com.tuapp.eventfoto.message.Message.builder()
                    .event(testEvent).authorName("Invitado").text("Hola " + i).isApproved(true).build());
        }

        for (String url : new String[]{"/api/v1/events/evento-demo-k7m2xq9p/photos", "/api/v1/events/evento-demo-k7m2xq9p/messages"}) {
            mockMvc.perform(get(url).param("size", "100000"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(100)));
            mockMvc.perform(get(url).param("size", "0"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(1)));
        }
        mockMvc.perform(get("/api/v1/admin/events/evento-demo-k7m2xq9p/photos").param("size", "100000")
                        .header("Authorization", "Bearer " + adminJwtToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(100)));
    }

    /** Clave con el formato events/{eventId}/{uuid}.ext (el único que acepta /confirm). */
    private String key() {
        return "events/" + testEvent.getId() + "/" + java.util.UUID.randomUUID() + ".jpg";
    }
}
