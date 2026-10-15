package com.tuapp.eventfoto.common.email;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * En producción (app.storage.mode=r2) no arranca sin mails reales: un mail de recuperación de contraseña que solo se
 * loguea nadie lo nota (misma lección que el APP_BASE_URL de ejemplo, ver AppUrls). Exige MAIL_PROVIDER=resend,
 * RESEND_API_KEY y MAIL_REPLY_TO (el dominio no recibe mails: las respuestas tienen que ir a una casilla real).
 * El mensaje nombra la VARIABLE, nunca el valor.
 */
@Slf4j
@Component
public class MailConfigurationValidator {

    private final String storageMode;
    private final String provider;
    private final String resendApiKey;
    private final String replyTo;

    public MailConfigurationValidator(@Value("${app.storage.mode:local}") String storageMode,
                                      @Value("${app.mail.provider:log}") String provider,
                                      @Value("${app.mail.resend-api-key:}") String resendApiKey,
                                      @Value("${app.mail.reply-to:}") String replyTo) {
        this.storageMode = storageMode;
        this.provider = provider;
        this.resendApiKey = resendApiKey;
        this.replyTo = replyTo;
    }

    @PostConstruct
    void validateOnStartup() {
        if (!"r2".equalsIgnoreCase(trim(storageMode))) {
            return;
        }
        List<String> problems = findProblems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Producción (app.storage.mode=r2) sin configuración de mail válida:\n  - "
                    + String.join("\n  - ", problems) + "\n\nCargá las variables en Railway (ver README, \"Mails\").");
        }
        log.info("Mails de producción: provider=resend, Reply-To configurado.");
    }

    List<String> findProblems() {
        List<String> problems = new ArrayList<>();
        if (!"resend".equalsIgnoreCase(trim(provider))) {
            problems.add("MAIL_PROVIDER: tiene que ser 'resend' (con 'log' los mails no salen)");
        }
        if (trim(resendApiKey).isEmpty()) {
            problems.add("RESEND_API_KEY: no está seteada");
        }
        if (trim(replyTo).isEmpty()) {
            problems.add("MAIL_REPLY_TO: no está seteada");
        }
        return problems;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
