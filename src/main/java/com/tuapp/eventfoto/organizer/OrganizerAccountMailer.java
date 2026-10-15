package com.tuapp.eventfoto.organizer;

import com.tuapp.eventfoto.common.email.EmailService;
import com.tuapp.eventfoto.common.email.EmailTemplates;
import com.tuapp.eventfoto.common.email.MailExecutor;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mails de la cuenta del organizador: activación y recuperación de contraseña. La referencia que se loguea es el id
 * del organizador, nunca la dirección ni el link (que es la credencial).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrganizerAccountMailer {

    static final String ACTIVATION_SUBJECT = "Activá tu cuenta de EventFoto";
    static final String PASSWORD_RESET_SUBJECT = "Elegí una contraseña nueva para EventFoto";

    private final EmailService emailService;
    private final EmailTemplates emailTemplates;
    private final MailExecutor executor;

    /**
     * Después del commit y fuera del hilo del request: el superadmin no espera a Resend y un envío fallido no rompe
     * la creación del evento ni la del link, que el superadmin igual ve en pantalla.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onActivationLinkIssued(ActivationLinkIssued issued) {
        executor.execute(() -> {
            try {
                Map<String, Object> variables = new HashMap<>();
                variables.put("activationUrl", issued.activationUrl());
                variables.put("eventName", issued.eventName());
                emailService.send(emailTemplates.render(issued.email(), ACTIVATION_SUBJECT, "account-activation", variables,
                        "activation:" + issued.organizerId()));
            } catch (RuntimeException e) {
                log.error("No se pudo mandar el mail de activación del organizer {}; el link sigue visible en el panel del superadmin",
                        issued.organizerId(), e);
                Sentry.captureException(e);
            }
        });
    }

    /** @throws RuntimeException si el envío falla (lo maneja PasswordResetService). */
    public void sendPasswordReset(UUID organizerId, String email, String resetUrl) {
        emailService.send(emailTemplates.render(email, PASSWORD_RESET_SUBJECT, "password-reset", Map.of("resetUrl", resetUrl),
                "password-reset:" + organizerId));
    }
}
