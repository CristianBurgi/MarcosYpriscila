package com.tuapp.eventfoto.checkout;

import com.tuapp.eventfoto.common.config.AppUrls;
import com.tuapp.eventfoto.common.email.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.util.UUID;

/** Mail "Tu evento está listo": plantilla Thymeleaf, link al login desde AppUrls. Nunca la contraseña. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.checkout.enabled", havingValue = "true")
public class PurchaseConfirmationMailer {

    static final String SUBJECT = "Tu evento está listo";

    private final EmailService emailService;
    private final ITemplateEngine templateEngine;
    private final AppUrls appUrls;

    public void sendEventReady(String to, String eventName, boolean repurchase, UUID externalReference) {
        Context context = new Context();
        context.setVariable("eventName", eventName);
        context.setVariable("repurchase", repurchase);
        context.setVariable("loginUrl", appUrls.loginUrl());
        String html = templateEngine.process("email/event-ready", context);
        emailService.send(new EmailService.EmailMessage(to, SUBJECT, html, externalReference.toString()));
    }
}
