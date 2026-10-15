package com.tuapp.eventfoto.organizer;

import com.tuapp.eventfoto.common.exception.InvalidActivationTokenException;
import com.tuapp.eventfoto.testsupport.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tokens de un solo uso del organizador: activación de cuenta (9.1) y recuperación de contraseña (9.2-B).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MutableClock.Config.class)
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
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MutableClock clock;

    private Organizer organizer;

    @BeforeEach
    void setUp() {
        clock.set(Instant.now());
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
        token.setExpiresAt(clock.instant().minusSeconds(1));
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
    @DisplayName("Un link de activación no sirve para recuperar la contraseña")
    void activationTokenIsNotAPasswordResetToken() {
        var issued = organizerTokenService.issue(organizer, OrganizerTokenPurpose.ACCOUNT_ACTIVATION);

        assertThatThrownBy(() -> organizerTokenService.consume(issued.rawToken(), OrganizerTokenPurpose.PASSWORD_RESET))
                .isInstanceOf(InvalidActivationTokenException.class);
        assertThatThrownBy(() -> organizerTokenService.peek(issued.rawToken(), OrganizerTokenPurpose.PASSWORD_RESET))
                .isInstanceOf(InvalidActivationTokenException.class);
    }

    @Test
    @DisplayName("Vencimiento por propósito: activación 7 días, recuperación 1 hora (con el reloj de la app)")
    void ttlDependsOnPurpose() {
        Instant now = clock.instant();
        assertThat(organizerTokenService.issue(organizer, OrganizerTokenPurpose.ACCOUNT_ACTIVATION).expiresAt())
                .isEqualTo(now.plus(Duration.ofDays(7)));
        var reset = organizerTokenService.issue(organizer, OrganizerTokenPurpose.PASSWORD_RESET);
        assertThat(reset.expiresAt()).isEqualTo(now.plus(Duration.ofHours(1)));

        clock.set(now.plus(Duration.ofMinutes(61)));
        assertThatThrownBy(() -> organizerTokenService.consume(reset.rawToken(), OrganizerTokenPurpose.PASSWORD_RESET))
                .isInstanceOf(InvalidActivationTokenException.class);
    }

    @Test
    @DisplayName("Consumo atómico: si otro pedido gastó el token después de que este lo leyó, este se rechaza")
    void consumeIsAtomic() {
        var issued = organizerTokenService.issue(organizer, OrganizerTokenPurpose.PASSWORD_RESET);
        // Este "pedido" ya leyó el token (queda en el contexto de persistencia con used_at vacío)...
        OrganizerToken read = organizerTokenRepository.findAll().get(0);
        assertThat(read.getUsedAt()).isNull();
        // ...y otro lo gasta por su cuenta, sin pasar por este contexto.
        assertThat(jdbcTemplate.update("UPDATE organizer_token SET used_at = CURRENT_TIMESTAMP WHERE id = ?", read.getId())).isEqualTo(1);

        assertThatThrownBy(() -> organizerTokenService.consume(issued.rawToken(), OrganizerTokenPurpose.PASSWORD_RESET))
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
