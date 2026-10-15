package com.tuapp.eventfoto.common.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * app.mail.provider=log (el default): no manda nada, deja constancia de que el mail "se enviaría", con la referencia
 * y sin la dirección ni el cuerpo. En producción no arranca (MailConfigurationValidator).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "log", matchIfMissing = true)
public class LoggingEmailService implements EmailService {

    @Override
    public void send(EmailMessage message) {
        log.info("Mail '{}' que se enviaría (referencia {}); app.mail.provider=log, sin envío real", message.subject(), message.reference());
    }
}
