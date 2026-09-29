package com.tuapp.eventfoto.organizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuapp.eventfoto.admin.dto.LoginRequestDTO;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 9.1 Bloque 1: flujo end-to-end de activación -- una cuenta creada sin
 * contraseña (ver SuperadminEventService) no puede loguearse hasta que se activa
 * con su link de un solo uso; después de activarla, sí.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OrganizerActivationFlowTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private OrganizerRepository organizerRepository;
    @Autowired
    private OrganizerTokenService organizerTokenService;
    @Autowired
    private com.tuapp.eventfoto.common.config.RateLimiterService rateLimiterService;

    private Organizer organizer;

    @BeforeEach
    void setUp() {
        rateLimiterService.resetRateLimits();
        organizerRepository.deleteAll();
        organizer = organizerRepository.save(Organizer.builder().email("activation-flow@test.com").build());
    }

    @Test
    @DisplayName("Antes de activar, el login con cualquier contraseña devuelve 401")
    void loginBeforeActivationFails() throws Exception {
        LoginRequestDTO login = new LoginRequestDTO("activation-flow@test.com", "cualquier-cosa");
        mockMvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/organizer/activate con token inválido -> 400")
    void activateWithInvalidTokenFails() throws Exception {
        mockMvc.perform(post("/api/v1/organizer/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"no-existe\",\"password\":\"contraseña-nueva\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Activar con un token válido permite loguearse después con la contraseña elegida")
    void activateThenLoginSucceeds() throws Exception {
        var issued = organizerTokenService.issue(organizer, OrganizerTokenPurpose.ACCOUNT_ACTIVATION);

        mockMvc.perform(post("/api/v1/organizer/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + issued.rawToken() + "\",\"password\":\"contraseña-nueva-123\"}"))
                .andExpect(status().isOk());

        LoginRequestDTO login = new LoginRequestDTO("activation-flow@test.com", "contraseña-nueva-123");
        mockMvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("El mismo token no se puede usar dos veces para activar")
    void activateTwiceWithSameTokenFails() throws Exception {
        var issued = organizerTokenService.issue(organizer, OrganizerTokenPurpose.ACCOUNT_ACTIVATION);
        String body = "{\"token\":\"" + issued.rawToken() + "\",\"password\":\"contraseña-nueva-123\"}";

        mockMvc.perform(post("/api/v1/organizer/activate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/organizer/activate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
}
