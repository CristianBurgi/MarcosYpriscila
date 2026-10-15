package com.tuapp.eventfoto.checkout;

import com.tuapp.eventfoto.common.config.AppUrls;
import com.tuapp.eventfoto.common.email.EmailService;
import com.tuapp.eventfoto.common.email.EmailTemplates;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.UUID;

/** Mail "Tu evento está listo": plantilla Thymeleaf, link al login desde AppUrls. Nunca la contraseña. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.checkout.enabled", havingValue = "true")
public class PurchaseConfirmationMailer {

    static final String SUBJECT = "Tu evento está listo";

    private final EmailService emailService;
    private final EmailTemplates emailTemplates;
    private final AppUrls appUrls;

    public void sendEventReady(String to, String eventName, boolean repurchase, UUID externalReference) {
        emailService.send(emailTemplates.render(to, SUBJECT, "event-ready",
                Map.of("eventName", eventName, "repurchase", repurchase, "loginUrl", appUrls.loginUrl()),
                externalReference.toString()));
    }
}
