package com.tuapp.eventfoto.organizer;

import com.tuapp.eventfoto.common.config.AppUrls;
import com.tuapp.eventfoto.common.exception.InvalidPasswordException;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recuperación de contraseña del organizador (9.2-B). El superadmin no tiene: sus credenciales son variables de entorno
 * y no está en la tabla organizer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final OrganizerRepository organizerRepository;
    private final OrganizerTokenService organizerTokenService;
    private final OrganizerAccountMailer mailer;
    private final AppUrls appUrls;
    private final PasswordEncoder passwordEncoder;

    /**
     * Corre fuera del request (PasswordResetController ya respondió lo mismo para cualquier email): emite el token y
     * manda el mail solo si hay una cuenta ACTIVADA con ese email. Una sin activar no recibe nada (se activa con el link
     * del superadmin). Nunca lanza: un fallo queda en el log y en Sentry, sin la dirección.
     */
    public void requestReset(String email) {
        try {
            Organizer organizer = organizerRepository.findByEmailIgnoreCase(email.trim().toLowerCase())
                    .filter(Organizer::isActivated)
                    .orElse(null);
            if (organizer == null) {
                log.info("Recuperación de contraseña pedida para un email sin cuenta activada; no se manda nada");
                return;
            }
            var issued = organizerTokenService.issue(organizer, OrganizerTokenPurpose.PASSWORD_RESET);
            mailer.sendPasswordReset(organizer.getId(), organizer.getEmail(), appUrls.passwordResetUrl(issued.rawToken()));
            log.info("Mail de recuperación de contraseña enviado al organizer {}", organizer.getId());
        } catch (RuntimeException e) {
            log.error("Falló la recuperación de contraseña pedida", e);
            Sentry.captureException(e);
        }
    }

    /**
     * Cambia la contraseña con un link de recuperación. La política se valida contra el email del dueño ANTES de
     * consumir el token (una contraseña rechazada no gasta el link). tokenVersion++ en la misma transacción: toda
     * sesión abierta con la contraseña vieja deja de valer.
     *
     * @return el email de la cuenta, para completar el login
     */
    @Transactional
    public String resetPassword(String rawToken, String newPassword) {
        Organizer owner = organizerTokenService.peek(rawToken, OrganizerTokenPurpose.PASSWORD_RESET);
        PasswordPolicy.violation(newPassword, owner.getEmail()).ifPresent(message -> {
            throw new InvalidPasswordException(message);
        });
        Organizer organizer = organizerTokenService.consume(rawToken, OrganizerTokenPurpose.PASSWORD_RESET);
        organizer.setPasswordHash(passwordEncoder.encode(newPassword));
        organizer.setTokenVersion(organizer.getTokenVersion() + 1);
        organizerRepository.save(organizer);
        log.info("Contraseña cambiada por recuperación para el organizer {}; sesiones anteriores invalidadas", organizer.getId());
        return organizer.getEmail();
    }
}
