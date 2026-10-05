package com.tuapp.eventfoto.common.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** No manda nada: deja constancia de que el mail "se enviaría", con la referencia y sin la dirección ni el cuerpo. */
@Slf4j
@Component
public class LoggingEmailService implements EmailService {

    @Override
    public void send(EmailMessage message) {
        log.info("Mail '{}' que se enviaría (referencia {}); sin envío real hasta la 9.2-B", message.subject(), message.reference());
    }
}
