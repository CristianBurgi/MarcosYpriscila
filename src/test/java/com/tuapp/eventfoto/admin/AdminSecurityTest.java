package com.tuapp.eventfoto.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuapp.eventfoto.admin.dto.LoginRequestDTO;
import com.tuapp.eventfoto.common.config.JwtAuthenticationFilter;
import com.tuapp.eventfoto.common.config.JwtTokenProvider;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventRepository;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

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
    private JwtTokenProvider jwtTokenProvider;

    private Event event;
    private String adminJwtToken;

    @BeforeEach
    void setUp() {
        photoRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();

        event = eventRepository.saveAndFlush(Event.builder()
                .name("Boda de Marcos y Priscila")
                .slug("marcos-y-priscila")
                .eventDate(Instant.now().plusSeconds(86400))
                .uploadDeadline(Instant.now().plusSeconds(864000))
                .isActive(true)
                .build());

        adminJwtToken = jwtTokenProvider.generateToken("admin@boda.com");
    }

    @Test
    @DisplayName("Redirigir a /admin/login al intentar acceder al dashboard sin JWT")
    void shouldRedirectToLoginWithoutJwt() throws Exception {
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    @DisplayName("Devolver 401 Unauthorized al consultar API admin sin token JWT")
    void shouldReturn401ForAdminApiWithoutJwt() throws Exception {
        mockMvc.perform(get("/api/v1/admin/photos/pending"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Login exitoso devuelve 200 OK y setea cookie JWT-TOKEN")
    void shouldAuthenticateAdminSuccessfully() throws Exception {
        LoginRequestDTO loginRequest = new LoginRequestDTO("admin@boda.com", "admin123");

        mockMvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(cookie().exists(JwtAuthenticationFilter.COOKIE_NAME))
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.email").value("admin@boda.com"));
    }

    @Test
    @DisplayName("Login fallido devuelve 401 Unauthorized con contraseña incorrecta")
    void shouldFailLoginWithBadCredentials() throws Exception {
        LoginRequestDTO loginRequest = new LoginRequestDTO("admin@boda.com", "wrongpassword");

        mockMvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Acceso concedido al dashboard con Cookie JWT válida")
    void shouldAccessDashboardWithJwtCookie() throws Exception {
        mockMvc.perform(get("/admin/dashboard")
                        .cookie(new Cookie(JwtAuthenticationFilter.COOKIE_NAME, adminJwtToken)))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/dashboard"))
                .andExpect(model().attributeExists("pendingCount", "totalPhotos", "totalMessages"));
    }

    @Test
    @DisplayName("Aprobar una foto individual mediante API Admin")
    void shouldApproveSinglePhoto() throws Exception {
        Photo pendingPhoto = photoRepository.saveAndFlush(Photo.builder()
                .event(event)
                .storageKey("photos/marcos-y-priscila/test.jpg")
                .uploaderName("María")
                .isApproved(false)
                .build());

        assertFalse(pendingPhoto.isApproved());

        mockMvc.perform(patch("/api/v1/admin/photos/" + pendingPhoto.getId() + "/approve")
                        .header("Authorization", "Bearer " + adminJwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isApproved").value(true));

        Photo updatedPhoto = photoRepository.findById(pendingPhoto.getId()).orElseThrow();
        assertTrue(updatedPhoto.isApproved());
    }

    @Test
    @DisplayName("Rechazar y eliminar una foto individual mediante API Admin")
    void shouldRejectSinglePhoto() throws Exception {
        Photo pendingPhoto = photoRepository.saveAndFlush(Photo.builder()
                .event(event)
                .storageKey("photos/marcos-y-priscila/bad.jpg")
                .uploaderName("Spam")
                .isApproved(false)
                .build());

        mockMvc.perform(delete("/api/v1/admin/photos/" + pendingPhoto.getId())
                        .header("Authorization", "Bearer " + adminJwtToken))
                .andExpect(status().isNoContent());

        assertFalse(photoRepository.existsById(pendingPhoto.getId()));
    }

    @Test
    @DisplayName("Aprobar masivamente todas las fotos pendientes del evento")
    void shouldApproveAllPendingPhotos() throws Exception {
        photoRepository.save(Photo.builder().event(event).storageKey("p1.jpg").isApproved(false).build());
        photoRepository.save(Photo.builder().event(event).storageKey("p2.jpg").isApproved(false).build());
        photoRepository.saveAndFlush(Photo.builder().event(event).storageKey("p3.jpg").isApproved(false).build());

        assertEquals(3, photoRepository.countByEventIdAndIsApprovedFalse(event.getId()));

        mockMvc.perform(post("/api/v1/admin/photos/approve-all?slug=" + event.getSlug())
                        .header("Authorization", "Bearer " + adminJwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        assertEquals(0, photoRepository.countByEventIdAndIsApprovedFalse(event.getId()));
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
}
