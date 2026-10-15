package com.tuapp.eventfoto.common.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * app.mail.provider=resend: manda por la API HTTP de Resend (POST /emails) con RestClient, sin el SDK (traería OkHttp 5
 * y Kotlin 2, que chocan con las versiones que fija Spring Boot). Timeouts explícitos porque el mail de pago sale en el
 * hilo del webhook; sin reintentos: quien llama decide (EmailService).
 *
 * La API key va solo en el header Authorization: nunca en un log ni en el mensaje de una excepción. Los errores
 * tampoco copian el cuerpo de la respuesta de Resend, que puede repetir la dirección del destinatario.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "resend")
public class ResendEmailService implements EmailService {

    static final String SEND_URL = "https://api.resend.com/emails";
    static final int CONNECT_TIMEOUT_MS = 3_000;
    static final int READ_TIMEOUT_MS = 8_000;

    private final RestClient restClient;
    private final String apiKey;
    private final String from;
    private final String replyTo;

    @Autowired
    public ResendEmailService(@Value("${app.mail.resend-api-key:}") String apiKey,
                              @Value("${app.mail.from:}") String from,
                              @Value("${app.mail.reply-to:}") String replyTo,
                              RestClient.Builder builder) {
        this(apiKey, from, replyTo, builder.requestFactory(timeouts()).build());
    }

    ResendEmailService(String apiKey, String from, String replyTo, RestClient restClient) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("RESEND_API_KEY no está seteada y app.mail.provider=resend (MAIL_PROVIDER).");
        }
        if (from == null || from.isBlank()) {
            throw new IllegalStateException("MAIL_FROM no está seteada y app.mail.provider=resend (MAIL_PROVIDER).");
        }
        this.apiKey = apiKey.trim();
        this.from = from.trim();
        this.replyTo = replyTo == null ? "" : replyTo.trim();
        this.restClient = restClient;
    }

    static SimpleClientHttpRequestFactory timeouts() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return factory;
    }

    @Override
    public void send(EmailMessage message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", from);
        body.put("to", List.of(message.to()));
        body.put("subject", message.subject());
        body.put("html", message.htmlBody());
        body.put("text", message.textBody());
        if (!replyTo.isEmpty()) {
            body.put("reply_to", List.of(replyTo));
        }

        Map<?, ?> response;
        try {
            response = restClient.post()
                    .uri(SEND_URL)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("Resend rechazó el mail '" + message.subject() + "' (referencia "
                    + message.reference() + "): HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new IllegalStateException("No se pudo contactar a Resend para el mail '" + message.subject()
                    + "' (referencia " + message.reference() + "): " + e.getClass().getSimpleName());
        }
        log.info("Mail '{}' enviado por Resend (referencia {}, id {})", message.subject(), message.reference(),
                response == null ? null : response.get("id"));
    }
}
