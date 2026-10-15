package com.tuapp.eventfoto.common.email;

/**
 * Envío de mails transaccionales. app.mail.provider elige la implementación: {@link LoggingEmailService} (log, el
 * default de desarrollo y tests: no manda nada) o {@link ResendEmailService} (resend, obligatorio en producción, ver
 * MailConfigurationValidator). Una implementación real puede fallar: quien la llama decide qué hacer (ver
 * PurchaseConfirmationService, que libera la reserva para reintentar). Los cuerpos se arman con {@link EmailTemplates}.
 */
public interface EmailService {

    /**
     * @param message destinatario, asunto y cuerpos HTML y texto plano ya armados (plantillas Thymeleaf, links desde AppUrls);
     *                {@code reference} es lo único que se puede loguear (nunca la dirección ni el cuerpo)
     * @throws RuntimeException si el envío falla
     */
    void send(EmailMessage message);

    record EmailMessage(String to, String subject, String htmlBody, String textBody, String reference) {
        @Override
        public String toString() {
            // Nunca la dirección ni el cuerpo, ni por accidente en un log.
            return "EmailMessage[subject=" + subject + ", reference=" + reference + "]";
        }
    }
}
