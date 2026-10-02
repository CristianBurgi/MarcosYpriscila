package com.tuapp.eventfoto.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuapp.eventfoto.admin.dto.LoginRequestDTO;
import com.tuapp.eventfoto.common.config.JwtAuthenticationFilter;
import com.tuapp.eventfoto.common.config.JwtTokenProvider;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.photo.Photo;
import com.tuapp.eventfoto.photo.PhotoRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private PhotoRepository photoRepository;

    @Autowired
    private OrganizerRepository organizerRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private com.tuapp.eventfoto.common.config.RateLimiterService rateLimiterService;

    private Event event;
    private Organizer organizer;
    private String adminJwtToken;

    @BeforeEach
    void setUp() {
        rateLimiterService.resetRateLimits();
        photoRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();
        organizerRepository.deleteAllInBatch();

        organizer = organizerRepository.saveAndFlush(Organizer.builder()
                .email("admin-security@test.com")
                .passwordHash(passwordEncoder.encode("admin123"))
                .build());

        event = eventRepository.saveAndFlush(Event.builder()
                .organizer(organizer)
                .name("Evento de Prueba")
                .slug("evento-demo-k7m2xq9p")
                .eventDate(LocalDate.now().plusDays(1))
                .uploadDeadline(Instant.now().plusSeconds(864000))
                .isActive(true)
                .origin(EventOrigin.PAID)
                .build());

        adminJwtToken = jwtTokenProvider.generateOrganizerToken(organizer.getId(), organizer.getEmail(), organizer.getTokenVersion());
    }


    @Test
    @DisplayName("Redirigir a /admin/login al intentar acceder al dashboard sin JWT")
    void shouldRedirectToLoginWithoutJwt() throws Exception {
        mockMvc.perform(get("/admin/eventos/" + event.getSlug()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    @DisplayName("Devolver 401 Unauthorized al consultar API admin sin token JWT")
    void shouldReturn401ForAdminApiWithoutJwt() throws Exception {
        mockMvc.perform(get("/api/v1/admin/events/" + event.getSlug() + "/photos"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Login exitoso devuelve 200 OK y setea cookie JWT-TOKEN")
    void shouldAuthenticateAdminSuccessfully() throws Exception {
        LoginRequestDTO loginRequest = new LoginRequestDTO("admin-security@test.com", "admin123");

        mockMvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(cookie().exists(JwtAuthenticationFilter.ORGANIZER_COOKIE_NAME))
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.email").value("admin-security@test.com"));
    }

    @Test
    @DisplayName("Login fallido devuelve 401 Unauthorized con contraseña incorrecta")
    void shouldFailLoginWithBadCredentials() throws Exception {
        LoginRequestDTO loginRequest = new LoginRequestDTO("admin-security@test.com", "wrongpassword");

        mockMvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Acceso concedido al dashboard con Cookie JWT válida")
    void shouldAccessDashboardWithJwtCookie() throws Exception {
        mockMvc.perform(get("/admin/eventos/" + event.getSlug())
                        .cookie(new Cookie(JwtAuthenticationFilter.ORGANIZER_COOKIE_NAME, adminJwtToken)))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/dashboard"))
                .andExpect(model().attributeExists("photos", "totalPhotos", "totalMessages", "guestMenuUrl"))
                .andExpect(model().attributeDoesNotExist("pendingCount", "pendingPhotos"));
    }

    @Test
    @DisplayName("Listar las fotos publicadas del evento mediante API Admin (sin paso de aprobación)")
    void shouldListPublishedPhotos() throws Exception {
        Photo photo = photoRepository.saveAndFlush(Photo.builder()
                .event(event)
                .storageKey("photos/evento-demo-k7m2xq9p/test.jpg")
                .uploaderName("María")
                .build());

        mockMvc.perform(get("/api/v1/admin/events/" + event.getSlug() + "/photos")
                        .header("Authorization", "Bearer " + adminJwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(photo.getId().toString()))
                .andExpect(jsonPath("$.content[0].isApproved").doesNotExist());
    }

    @Test
    @DisplayName("Eliminar una foto individual mediante API Admin (único control de moderación)")
    void shouldDeleteSinglePhoto() throws Exception {
        Photo photo = photoRepository.saveAndFlush(Photo.builder()
                .event(event)
                .storageKey("photos/evento-demo-k7m2xq9p/bad.jpg")
                .uploaderName("Spam")
                .build());

        mockMvc.perform(delete("/api/v1/admin/events/" + event.getSlug() + "/photos/" + photo.getId())
                        .header("Authorization", "Bearer " + adminJwtToken))
                .andExpect(status().isNoContent());

        assertFalse(photoRepository.existsById(photo.getId()));
    }

    @Test
    @DisplayName("Devolver 404 Not Found al intentar eliminar un photoId inexistente")
    void shouldReturn404WhenDeletingNonExistentPhoto() throws Exception {
        java.util.UUID randomId = java.util.UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/admin/events/" + event.getSlug() + "/photos/" + randomId)
                        .header("Authorization", "Bearer " + adminJwtToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Los endpoints de aprobación manual ya no existen (Fase 9.0)")
    void shouldNotExposeApprovalEndpoints() throws Exception {
        Photo photo = photoRepository.saveAndFlush(Photo.builder().event(event).storageKey("p1.jpg").build());

        mockMvc.perform(patch("/api/v1/admin/events/" + event.getSlug() + "/photos/" + photo.getId() + "/approve")
                        .header("Authorization", "Bearer " + adminJwtToken))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(post("/api/v1/admin/events/" + event.getSlug() + "/photos/approve-all")
                        .header("Authorization", "Bearer " + adminJwtToken))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/api/v1/admin/events/" + event.getSlug() + "/photos/pending")
                        .header("Authorization", "Bearer " + adminJwtToken))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("Alternar el estado activo/cerrado del evento mediante API Admin")
    void shouldToggleEventStatus() throws Exception {
        assertTrue(event.isActive());

        mockMvc.perform(patch("/api/v1/admin/events/" + event.getSlug() + "/toggle-status")
                        .header("Authorization", "Bearer " + adminJwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(false));

        Event updatedEvent = eventRepository.findById(event.getId()).orElseThrow();
        assertFalse(updatedEvent.isActive());
    }

    @Test
    @DisplayName("Redirigir HTTP 302 para descarga individual de foto")
    void shouldRedirectForSinglePhotoDownload() throws Exception {
        Photo photo = photoRepository.saveAndFlush(Photo.builder()
                .event(event)
                .storageKey("photos/evento-demo-k7m2xq9p/photo.jpg")
                .uploaderName("Carlos")
                .build());

        mockMvc.perform(get("/api/v1/admin/events/" + event.getSlug() + "/photos/" + photo.getId() + "/download")
                        .header("Authorization", "Bearer " + adminJwtToken))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("Generar descarga ZIP de fotos mediante streaming")
    void shouldStreamPhotosZip() throws Exception {
        photoRepository.save(Photo.builder().event(event).storageKey("p1.jpg").build());
        photoRepository.saveAndFlush(Photo.builder().event(event).storageKey("p2.jpg").build());

        mockMvc.perform(get("/api/v1/admin/events/" + event.getSlug() + "/photos/download-zip")
                        .header("Authorization", "Bearer " + adminJwtToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"album-evento-demo-k7m2xq9p.zip\""));
    }

    @Test
    @DisplayName("Validar encabezado Set-Cookie con atributos HttpOnly, Secure y SameSite=Strict en login")
    void shouldReturnSetCookieHeaderWithSameSiteStrictOnLogin() throws Exception {
        LoginRequestDTO loginRequest = new LoginRequestDTO("admin-security@test.com", "admin123");

        mockMvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("SameSite=Strict")))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Secure")));
    }

    @Test
    @DisplayName("Bloquear intentos de login si superan el límite de rate limit por IP (429 Too Many Requests)")
    void shouldBlockLoginAfterMaxFailedAttempts() throws Exception {
        LoginRequestDTO badRequest = new LoginRequestDTO("admin-security@test.com", "wrongpass");

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/admin/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(badRequest)))
                    .andExpect(status().isUnauthorized());
        }

        // El sexto intento debe ser bloqueado por RateLimiterService (HTTP 429)
        mockMvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badRequest)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("Un X-Real-IP inventado y distinto en cada intento no evita el bloqueo del rate limit (remoteAddr en tests no es un hop interno de Railway, así que el header se ignora)")
    void fabricatedXForwardedForDoesNotEvadeLoginRateLimit() throws Exception {
        LoginRequestDTO badRequest = new LoginRequestDTO("admin-security@test.com", "wrongpass");

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/admin/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(badRequest))
                            .header("X-Real-IP", "10.0.0." + i)) // el atacante cambia el header en cada intento
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badRequest))
                        .header("X-Real-IP", "10.0.0.99"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("Permitir acceso público únicamente a GET /actuator/health y proteger otros endpoints de Actuator")
    void shouldAllowPublicAccessToActuatorHealthOnly() throws Exception {
        // GET /actuator/health debe responder HTTP 200 OK
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        // GET /actuator/env no debe expenerse públicamente (devuelve 401, 302 o 404 si no está expuesto)
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().is(org.hamcrest.Matchers.in(java.util.List.of(401, 302, 404))));
    }

    @Test
    @DisplayName("Permitir que un invitado suba fotos localmente vía PUT /api/v1/storage/local-upload sin JWT en modo local")
    void shouldAllowGuestLocalUploadWithoutJwt() throws Exception {
        // Firma JPEG válida (FF D8 FF...) para pasar la verificación de magic bytes:
        // este test valida que el endpoint sea público (permitAll), no la validación de contenido.
        byte[] content = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};
        mockMvc.perform(put("/api/v1/storage/local-upload?key=events/00000000-0000-4000-8000-0000000000aa/00000000-0000-4000-8000-0000000000bb.jpg")
                        .content(content))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Aplicar default-deny (.anyRequest().authenticated()) a rutas no mapeadas")
    void shouldEnforceDefaultDenyForUnknownRoutes() throws Exception {
        mockMvc.perform(get("/api/v1/unmapped-private-route"))
                .andExpect(status().isUnauthorized());
    }
}


