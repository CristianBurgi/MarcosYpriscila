package com.tuapp.eventfoto.organizer;

import com.tuapp.eventfoto.common.exception.InvalidActivationTokenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fase 9.1 Bloque 1: tokens de un solo uso del organizador (activación de cuenta).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OrganizerTokenServiceTest {

    @Autowired
    private OrganizerTokenService organizerTokenService;
    @Autowired
    private OrganizerTokenRepository organizerTokenRepository;
    @Autowired
    private OrganizerRepository organizerRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private Organizer organizer;

    @BeforeEach
    void setUp() {
        organizerTokenRepository.deleteAll();
        organizerRepository.deleteAll();
        organizer = organizerRepository.save(Organizer.builder().email("token-test@test.com").build());
    }

    @Test
    @DisplayName("Un token recién emitido se consume una sola vez y devuelve el organizador")
    void issuedTokenCanBeConsumedOnce() {
        var issued = organizerTokenService.issue(organizer, OrganizerTokenPurpose.ACCOUNT_ACTIVATION);

        Organizer resolved = organizerTokenService.consume(issued.rawToken(), OrganizerTokenPurpose.ACCOUNT_ACTIVATION);
        assertThat(resolved.getId()).isEqualTo(organizer.getId());
    }

    @Test
    @DisplayName("Un token ya usado se rechaza (no se puede activar dos veces con el mismo link)")
    void usedTokenIsRejected() {
        var issued = organizerTokenService.issue(organizer, OrganizerTokenPurpose.ACCOUNT_ACTIVATION);
        organizerTokenService.consume(issued.rawToken(), OrganizerTokenPurpose.ACCOUNT_ACTIVATION);

        assertThatThrownBy(() -> organizerTokenService.consume(issued.rawToken(), OrganizerTokenPurpose.ACCOUNT_ACTIVATION))
                .isInstanceOf(InvalidActivationTokenException.class);
    }

    @Test
    @DisplayName("Un token vencido se rechaza")
    void expiredTokenIsRejected() {
        var issued = organizerTokenService.issue(organizer, OrganizerTokenPurpose.ACCOUNT_ACTIVATION);
        OrganizerToken token = organizerTokenRepository.findAll().get(0);
        token.setExpiresAt(Instant.now().minusSeconds(1));
        organizerTokenRepository.save(token);

        assertThatThrownBy(() -> organizerTokenService.consume(issued.rawToken(), OrganizerTokenPurpose.ACCOUNT_ACTIVATION))
                .isInstanceOf(InvalidActivationTokenException.class);
    }

    @Test
    @DisplayName("Un token inexistente (nunca emitido) se rechaza")
    void unknownTokenIsRejected() {
        assertThatThrownBy(() -> organizerTokenService.consume("token-que-nunca-existio", OrganizerTokenPurpose.ACCOUNT_ACTIVATION))
                .isInstanceOf(InvalidActivationTokenException.class);
    }

    @Test
    @DisplayName("Generar un token nuevo invalida el anterior sin usar")
    void issuingNewTokenInvalidatesThePrevious() {
        var first = organizerTokenService.issue(organizer, OrganizerTokenPurpose.ACCOUNT_ACTIVATION);
        var second = organizerTokenService.issue(organizer, OrganizerTokenPurpose.ACCOUNT_ACTIVATION);

        assertThatThrownBy(() -> organizerTokenService.consume(first.rawToken(), OrganizerTokenPurpose.ACCOUNT_ACTIVATION))
                .isInstanceOf(InvalidActivationTokenException.class);

        Organizer resolved = organizerTokenService.consume(second.rawToken(), OrganizerTokenPurpose.ACCOUNT_ACTIVATION);
        assertThat(resolved.getId()).isEqualTo(organizer.getId());
    }

    @Test
    @DisplayName("Un token de otro propósito (ej. PASSWORD_RESET) no sirve para activar la cuenta")
    void tokenWithWrongPurposeIsRejected() {
        var issued = organizerTokenService.issue(organizer, OrganizerTokenPurpose.PASSWORD_RESET);

        assertThatThrownBy(() -> organizerTokenService.consume(issued.rawToken(), OrganizerTokenPurpose.ACCOUNT_ACTIVATION))
                .isInstanceOf(InvalidActivationTokenException.class);
    }

    @Test
    @DisplayName("Una cuenta sin activar (passwordHash null) no puede loguearse")
    void unactivatedAccountCannotLogin() {
        assertThat(organizer.isActivated()).isFalse();
        assertThat(organizer.getPasswordHash()).isNull();
    }

    @Test
    @DisplayName("Después de activar (setear passwordHash), la cuenta queda marcada como activada")
    void activatingSetsPasswordAndActivatesAccount() {
        var issued = organizerTokenService.issue(organizer, OrganizerTokenPurpose.ACCOUNT_ACTIVATION);
        Organizer resolved = organizerTokenService.consume(issued.rawToken(), OrganizerTokenPurpose.ACCOUNT_ACTIVATION);
        resolved.setPasswordHash(passwordEncoder.encode("nueva-contraseña-123"));
        organizerRepository.save(resolved);

        Organizer reloaded = organizerRepository.findById(organizer.getId()).orElseThrow();
        assertThat(reloaded.isActivated()).isTrue();
    }
}
