package com.tuapp.eventfoto.common.email;

/**
 * Envío de mails transaccionales. Hoy la única implementación es {@link LoggingEmailService} (no manda nada); la de
 * Resend llega con el Bloque B de la 9.2, cuando haya dominio verificado. Una implementación real puede fallar: quien
 * la llama decide qué hacer (ver PurchaseConfirmationService, que libera la reserva para reintentar).
 */
public interface EmailService {

    /**
     * @param message destinatario, asunto y cuerpo HTML ya armados (plantilla Thymeleaf, links desde AppUrls);
     *                {@code reference} es lo único que se puede loguear (nunca la dirección ni el cuerpo)
     * @throws RuntimeException si el envío falla
     */
    void send(EmailMessage message);

    record EmailMessage(String to, String subject, String htmlBody, String reference) {
        @Override
        public String toString() {
            // Nunca la dirección ni el cuerpo, ni por accidente en un log.
            return "EmailMessage[subject=" + subject + ", reference=" + reference + "]";
        }
    }
}
