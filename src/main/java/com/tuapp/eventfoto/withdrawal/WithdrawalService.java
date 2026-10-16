package com.tuapp.eventfoto.withdrawal;

import com.tuapp.eventfoto.common.config.RateLimiterService;
import com.tuapp.eventfoto.common.email.EmailService;
import com.tuapp.eventfoto.common.email.EmailTemplates;
import com.tuapp.eventfoto.common.email.MailExecutor;
import com.tuapp.eventfoto.event.UploadWindow;
import io.sentry.Sentry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Botón de arrepentimiento: valida, guarda el pedido y avisa por mail a EventFoto y a quien lo pidió. Nunca consulta
 * cuentas ni eventos, así que la respuesta no puede revelar si existen. El pedido se guarda primero: los mails salen
 * después, fuera del hilo del request, y un envío fallido queda en el log (solo el número de pedido) y en Sentry.
 * Los asuntos no llevan texto de la persona: las excepciones del envío los incluyen. El recibo sale como mucho una vez
 * por hora por email (el formulario no sirve para llenar una casilla ajena); la persona ve lo mismo en los dos casos.
 */
@Slf4j
@Service
public class WithdrawalService {

    static final int MAX_EMAIL = 255;
    static final int MAX_EVENT_NAME = 100;
    static final int MAX_MESSAGE = 1000;
    static final String NOTICE_SUBJECT = "Nuevo pedido de arrepentimiento";
    static final String RECEIPT_SUBJECT = "Recibimos tu pedido de arrepentimiento";

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final DateTimeFormatter RECEIVED_AT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy 'a las' HH:mm").withZone(UploadWindow.ZONE);

    public record Form(String email, String eventName, String message) {
    }

    /** Lo que ve la persona en la confirmación y en su mail. */
    public record Receipt(String number, String receivedAt) {
    }

    private final WithdrawalRequestRepository repository;
    private final EmailService emailService;
    private final EmailTemplates emailTemplates;
    private final MailExecutor executor;
    private final RateLimiterService rateLimiterService;
    private final Clock clock;
    private final String notifyTo;

    public WithdrawalService(WithdrawalRequestRepository repository, EmailService emailService, EmailTemplates emailTemplates,
                             MailExecutor executor, RateLimiterService rateLimiterService, Clock clock,
                             @Value("${app.withdrawal.notify-to}") String notifyTo) {
        this.repository = repository;
        this.emailService = emailService;
        this.emailTemplates = emailTemplates;
        this.executor = executor;
        this.rateLimiterService = rateLimiterService;
        this.clock = clock;
        this.notifyTo = notifyTo;
    }

    /** Recorta los espacios; un mensaje vacío queda en null. */
    public static Form clean(String email, String eventName, String message) {
        String text = message == null ? "" : message.strip();
        return new Form(email == null ? "" : email.strip(), eventName == null ? "" : eventName.strip(), text.isEmpty() ? null : text);
    }

    /** Errores por campo (vacío = válido), con el texto que se muestra junto a cada uno. */
    public static Map<String, String> validate(Form form) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (form.email().isEmpty()) {
            errors.put("email", "Escribí el email de tu cuenta.");
        } else if (form.email().length() > MAX_EMAIL || !EMAIL.matcher(form.email()).matches()) {
            errors.put("email", "Revisá el email: no parece válido.");
        }
        if (form.eventName().isEmpty()) {
            errors.put("eventName", "Escribí el nombre del evento.");
        } else if (form.eventName().length() > MAX_EVENT_NAME) {
            errors.put("eventName", "El nombre del evento puede tener hasta " + MAX_EVENT_NAME + " caracteres.");
        }
        if (form.message() != null && form.message().length() > MAX_MESSAGE) {
            errors.put("message", "El mensaje puede tener hasta " + MAX_MESSAGE + " caracteres.");
        }
        return errors;
    }

    /** Guarda el pedido (ya validado) y programa los dos mails. */
    public Receipt submit(Form form) {
        WithdrawalRequest saved = repository.save(WithdrawalRequest.builder()
                .id(UUID.randomUUID())
                .email(form.email())
                .eventName(form.eventName())
                .message(form.message())
                .createdAt(clock.instant())
                .build());
        Receipt receipt = receiptOf(saved.getId(), saved.getCreatedAt());

        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("number", receipt.number());
        variables.put("receivedAt", receipt.receivedAt());
        variables.put("email", form.email());
        variables.put("eventName", form.eventName());
        variables.put("message", form.message());
        boolean sendReceipt = rateLimiterService.allowWithdrawalReceipt(form.email().toLowerCase(Locale.ROOT));
        executor.execute(() -> {
            send(notifyTo, NOTICE_SUBJECT + " " + receipt.number(), "withdrawal-notice", variables, receipt.number());
            if (sendReceipt) {
                send(form.email(), RECEIPT_SUBJECT, "withdrawal-receipt", variables, receipt.number());
            }
        });
        return receipt;
    }

    /** Lo que ve un bot que completó el honeypot: igual a una confirmación real, sin guardar ni mandar nada. */
    public Receipt fakeReceipt() {
        return receiptOf(UUID.randomUUID(), clock.instant());
    }

    private static Receipt receiptOf(UUID id, Instant createdAt) {
        return new Receipt(WithdrawalRequest.numberOf(id), RECEIVED_AT.format(createdAt));
    }

    private void send(String to, String subject, String template, Map<String, Object> variables, String number) {
        try {
            emailService.send(emailTemplates.render(to, subject, template, variables, template + ":" + number));
        } catch (RuntimeException e) {
            log.error("No se pudo mandar el mail {} del pedido de arrepentimiento {}; el pedido quedó guardado", template, number, e);
            Sentry.captureException(e);
        }
    }
}
