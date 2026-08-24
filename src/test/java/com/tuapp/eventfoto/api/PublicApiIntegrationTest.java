package com.tuapp.eventfoto.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuapp.eventfoto.comment.CommentRepository;
import com.tuapp.eventfoto.comment.dto.CreateCommentRequestDTO;
import com.tuapp.eventfoto.common.config.JwtTokenProvider;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventRepository;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.time.Instant;

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

        testEvent = Event.builder()
                .name("Boda de Marcos y Priscila")
                .slug("marcos-y-priscila")
                .eventDate(Instant.now().plusSeconds(86400))
                .uploadDeadline(Instant.now().plusSeconds(864000))
                .isActive(true)
                .build();

        eventRepository.save(testEvent);

        adminJwtToken = jwtTokenProvider.generateToken("admin@boda.com");

        when(storageService.generateUploadUrl(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn("https://r2.test-storage.com/upload-presigned-url");
        when(storageService.generatePublicUrl(org.mockito.ArgumentMatchers.any()))
                .thenReturn("https://r2.test-storage.com/public-photo-url.jpg");
        // Firma JPEG válida (FF D8 FF) para que la verificación de magic bytes en /confirm
        // (PhotoServiceImpl.validateUploadedFileSignature) pase con un StorageService mockeado.
        when(storageService.streamObject(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> new ByteArrayInputStream(
                        new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0}));
    }

    @Test
    @DisplayName("GET /api/v1/events/{slug} - Debe retornar 200 OK con los datos del evento")
    void shouldReturnEventDetails() throws Exception {
        mockMvc.perform(get("/api/v1/events/marcos-y-priscila"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Boda de Marcos y Priscila")))
                .andExpect(jsonPath("$.slug", is("marcos-y-priscila")));
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

        mockMvc.perform(post("/api/v1/events/marcos-y-priscila/photos/upload-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadUrl", notNullValue()))
                .andExpect(jsonPath("$.key", notNullValue()));
    }

    @Test
    @DisplayName("POST /api/v1/events/{slug}/photos/confirm - Debe registrar foto con isApproved=false")
    void shouldConfirmUploadAndCreateUnapprovedPhoto() throws Exception {
        ConfirmUploadRequestDTO request = new ConfirmUploadRequestDTO("photos/test.jpg", "Invitado Feliz", "¡Felicidades!", "guest-token-confirm-test");

        mockMvc.perform(post("/api/v1/events/marcos-y-priscila/photos/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isApproved", is(false)))
                .andExpect(jsonPath("$.uploaderName", is("Invitado Feliz")));
    }

    @Test
    @DisplayName("PATCH /api/v1/admin/photos/{id}/approve - Debe aprobar la foto para mostrarla en la galería")
    void shouldApprovePhoto() throws Exception {
        Photo photo = photoRepository.save(Photo.builder()
                .event(testEvent)
                .storageKey("photos/sample.jpg")
                .uploaderName("Caro")
                .isApproved(false)
                .build());

        mockMvc.perform(patch("/api/v1/admin/photos/" + photo.getId() + "/approve")
                        .header("Authorization", "Bearer " + adminJwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isApproved", is(true)));
    }

    @Test
    @DisplayName("POST /api/v1/photos/{photoId}/comments - Debe agregar comentario y responder 201 Created")
    void shouldAddCommentToPhoto() throws Exception {
        Photo photo = photoRepository.save(Photo.builder()
                .event(testEvent)
                .storageKey("photos/sample.jpg")
                .isApproved(true)
                .build());

        CreateCommentRequestDTO request = new CreateCommentRequestDTO("Tía Marta", "¡Qué hermosa foto!");

        mockMvc.perform(post("/api/v1/photos/" + photo.getId() + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorName", is("Tía Marta")))
                .andExpect(jsonPath("$.text", is("¡Qué hermosa foto!")));
    }

    @Test
    @DisplayName("POST /api/v1/photos/{photoId}/comments - Debe rechazar comentario vacío con 400 Bad Request")
    void shouldRejectEmptyComment() throws Exception {
        Photo photo = photoRepository.save(Photo.builder()
                .event(testEvent)
                .storageKey("photos/sample.jpg")
                .isApproved(true)
                .build());

        CreateCommentRequestDTO request = new CreateCommentRequestDTO("", "");

        mockMvc.perform(post("/api/v1/photos/" + photo.getId() + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }

    @Test
    @DisplayName("POST /api/v1/events/{slug}/messages - Debe registrar mensaje del libro de visitas")
    void shouldAddGuestbookMessage() throws Exception {
        CreateMessageRequestDTO request = new CreateMessageRequestDTO("Padrino Juan", "Les deseamos toda la felicidad del mundo en esta etapa.");

        mockMvc.perform(post("/api/v1/events/marcos-y-priscila/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorName", is("Padrino Juan")))
                .andExpect(jsonPath("$.text", containsString("toda la felicidad")));
    }

    @Test
    @DisplayName("DELETE /api/v1/admin/comments/{commentId} - Debe eliminar comentario por moderación de admin")
    void shouldDeleteCommentByAdmin() throws Exception {
        Photo photo = photoRepository.save(Photo.builder()
                .event(testEvent)
                .storageKey("photos/sample.jpg")
                .isApproved(true)
                .build());

        com.tuapp.eventfoto.comment.Comment comment = commentRepository.save(com.tuapp.eventfoto.comment.Comment.builder()
                .photo(photo)
                .authorName("Inapropiado")
                .text("Texto no permitido")
                .isApproved(true)
                .build());

        mockMvc.perform(delete("/api/v1/admin/comments/" + comment.getId())
                        .header("Authorization", "Bearer " + adminJwtToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("POST /api/v1/events/{slug}/messages - Debe rechazar mensaje ofensivo con 422 Unprocessable Entity")
    void shouldRejectProfaneMessageWith422() throws Exception {
        CreateMessageRequestDTO request = new CreateMessageRequestDTO("Spammer", "Sos un h.d.p.");

        mockMvc.perform(post("/api/v1/events/marcos-y-priscila/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status", is(422)))
                .andExpect(jsonPath("$.message", containsString("Tu mensaje no pudo publicarse")));
    }

    @Test
    @DisplayName("POST /api/v1/photos/{photoId}/comments - Debe rechazar comentario ofensivo con 422 Unprocessable Entity")
    void shouldRejectProfaneCommentWith422() throws Exception {
        Photo photo = photoRepository.save(Photo.builder()
                .event(testEvent)
                .storageKey("photos/sample.jpg")
                .isApproved(true)
                .build());

        CreateCommentRequestDTO request = new CreateCommentRequestDTO("Troll", "Sos una mierda");

        mockMvc.perform(post("/api/v1/photos/" + photo.getId() + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status", is(422)))
                .andExpect(jsonPath("$.message", containsString("Tu comentario no pudo publicarse")));
    }

    @Test
    @DisplayName("GET /api/v1/events/{slug}/guest-quota - Debe devolver 24 fotos disponibles para un token nuevo")
    void shouldReturnFullQuotaForNewGuestToken() throws Exception {
        mockMvc.perform(get("/api/v1/events/marcos-y-priscila/guest-quota")
                        .param("token", "guest-token-quota-fresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingPhotos", is(24)))
                .andExpect(jsonPath("$.maxPhotosPerGuest", is(24)));
    }

    @Test
    @DisplayName("GET /api/v1/events/{slug}/guest-quota - Debe decrecer tras confirmar una subida")
    void shouldDecrementQuotaAfterConfirm() throws Exception {
        String guestToken = "guest-token-quota-decrement";
        ConfirmUploadRequestDTO request = new ConfirmUploadRequestDTO("photos/quota-decrement.jpg", "Invitado", null, guestToken);

        mockMvc.perform(post("/api/v1/events/marcos-y-priscila/photos/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/events/marcos-y-priscila/guest-quota")
                        .param("token", guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingPhotos", is(23)));
    }

    @Test
    @DisplayName("POST /api/v1/events/{slug}/photos/confirm - Debe rechazar con 403 al superar el cupo de 24 fotos por invitado")
    void shouldRejectConfirmWithForbiddenWhenGuestQuotaExceeded() throws Exception {
        String guestToken = "guest-token-quota-limit";

        for (int i = 1; i <= 24; i++) {
            ConfirmUploadRequestDTO request = new ConfirmUploadRequestDTO("photos/quota-limit-" + i + ".jpg", "Invitado", null, guestToken);
            mockMvc.perform(post("/api/v1/events/marcos-y-priscila/photos/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        ConfirmUploadRequestDTO request25 = new ConfirmUploadRequestDTO("photos/quota-limit-25.jpg", "Invitado", null, guestToken);
        mockMvc.perform(post("/api/v1/events/marcos-y-priscila/photos/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request25)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.message", containsString("Ya usaste tus 24 fotos")));

        mockMvc.perform(get("/api/v1/events/marcos-y-priscila/guest-quota")
                        .param("token", guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingPhotos", is(0)));
    }

    @Test
    @DisplayName("POST /api/v1/events/{slug}/photos/upload-url - Debe rechazar con 403 si el invitado ya agotó su cupo")
    void shouldRejectUploadUrlWithForbiddenWhenGuestQuotaAlreadyExceeded() throws Exception {
        String guestToken = "guest-token-quota-upload-url";

        for (int i = 1; i <= 24; i++) {
            ConfirmUploadRequestDTO request = new ConfirmUploadRequestDTO("photos/quota-upload-url-" + i + ".jpg", "Invitado", null, guestToken);
            mockMvc.perform(post("/api/v1/events/marcos-y-priscila/photos/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        UploadUrlRequestDTO uploadUrlRequest = new UploadUrlRequestDTO("image/jpeg", "otra-mas.jpg", guestToken);
        mockMvc.perform(post("/api/v1/events/marcos-y-priscila/photos/upload-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(uploadUrlRequest)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.message", containsString("Ya usaste tus 24 fotos")));
    }

    @Test
    @DisplayName("DELETE /api/v1/admin/messages/{messageId} - Debe eliminar mensaje del libro de visitas por admin")
    void shouldDeleteMessageByAdmin() throws Exception {
        com.tuapp.eventfoto.message.Message message = messageRepository.save(com.tuapp.eventfoto.message.Message.builder()
                .event(testEvent)
                .authorName("Spammer")
                .text("Mensaje indeseado")
                .isApproved(true)
                .build());

        mockMvc.perform(delete("/api/v1/admin/messages/" + message.getId())
                        .header("Authorization", "Bearer " + adminJwtToken))
                .andExpect(status().isNoContent());
    }
}
