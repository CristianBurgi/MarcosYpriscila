package com.tuapp.eventfoto.organizer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fase 9.3: una sola regla de contraseñas, compartida por el checkout y la activación de cuenta. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordPolicyTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private OrganizerTokenService organizerTokenService;
    @Autowired private OrganizerTokenRepository organizerTokenRepository;

    private Organizer organizer;

    @BeforeEach
    void setUp() {
        organizerTokenRepository.deleteAll();
        organizerRepository.deleteAll();
        organizer = organizerRepository.save(Organizer.builder().email("policy@test.com").build());
    }

    @AfterEach
    void cleanUp() {
        organizerTokenRepository.deleteAll();
        organizerRepository.deleteAll();
    }

    @Test
    @DisplayName("La política: largo mínimo, máximo 72 BYTES (no caracteres), obvias y el propio email")
    void policyRules() {
        assertThat(PasswordPolicy.violation("corta", null)).isPresent();
        assertThat(PasswordPolicy.violation("una-clave-larga-y-rara-93", null)).isEmpty();
        assertThat(PasswordPolicy.violation("ab".repeat(36), null)).as("72 bytes justos").isEmpty();
        assertThat(PasswordPolicy.violation("ab".repeat(36) + "c", null)).isPresent();
        // 40 caracteres pero 80 bytes en UTF-8
        assertThat(PasswordPolicy.violation("ñ".repeat(40), null)).as("más de 72 bytes").isPresent();
        assertThat(PasswordPolicy.violation("Password123", null)).isPresent();
        assertThat(PasswordPolicy.violation("zzzzzzzzzz", null)).isPresent();
        assertThat(PasswordPolicy.violation("Mi.Mail@Test.com", "mi.mail@test.com")).isPresent();
    }

    private void activate(String token, String password) throws Exception {
        mockMvc.perform(post("/api/v1/organizer/activate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("La activación rechaza una contraseña de más de 72 bytes y una obvia, y el link no se gasta")
    void activationRejectsTooLongAndObviousPasswordsWithoutConsumingTheToken() throws Exception {
        var issued = organizerTokenService.issue(organizer, OrganizerTokenPurpose.ACCOUNT_ACTIVATION);

        activate(issued.rawToken(), "x".repeat(73));
        activate(issued.rawToken(), "ñ".repeat(40));
        activate(issued.rawToken(), "12345678");
        activate(issued.rawToken(), "password123");

        assertThat(organizerRepository.findById(organizer.getId()).orElseThrow().isActivated()).isFalse();

        mockMvc.perform(post("/api/v1/organizer/activate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + issued.rawToken() + "\",\"password\":\"una-clave-larga-y-rara-93\"}"))
                .andExpect(status().isOk());
        assertThat(organizerRepository.findById(organizer.getId()).orElseThrow().isActivated()).isTrue();
    }
}
