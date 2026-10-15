package com.tuapp.eventfoto.common.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class EmailTemplatesTest {

    private static final String LINK = "https://base.test/x?token=abc";

    @Autowired private EmailTemplates emailTemplates;

    @Test
    @DisplayName("Cada mail tiene versión HTML y versión de texto plano (sin etiquetas) con el mismo link")
    void everyMailHasHtmlAndText() {
        Map<String, Map<String, Object>> mails = Map.of(
                "event-ready", Map.of("eventName", "Boda X", "repurchase", false, "loginUrl", LINK),
                "expiry-reminder", Map.of("eventName", "Boda X", "deletionDay", "sábado 14 de diciembre", "loginUrl", LINK),
                "account-activation", withNullEvent(),
                "password-reset", Map.of("resetUrl", LINK));

        mails.forEach((template, variables) -> {
            EmailService.EmailMessage mail = emailTemplates.render("a@test.com", "Asunto", template, variables, "ref");
            assertThat(mail.htmlBody()).as(template).contains("<html").contains(LINK);
            assertThat(mail.textBody()).as(template).contains(LINK).doesNotContain("<").doesNotContain("th:");
        });
    }

    private static Map<String, Object> withNullEvent() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("activationUrl", LINK);
        variables.put("eventName", null);
        return variables;
    }
}
