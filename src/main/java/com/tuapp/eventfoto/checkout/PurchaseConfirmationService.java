package com.tuapp.eventfoto.checkout;

import com.tuapp.eventfoto.checkout.PaymentIncident.Reason;
import com.tuapp.eventfoto.checkout.PaymentLookupGateway.PaymentInfo;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventCreationService;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import io.sentry.Sentry;
import io.sentry.SentryLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Única puerta de confirmación de un pago: la usan el webhook y el regreso del navegador (y la 9.7 la va a reusar
 * para "marcar pago aprobado manualmente"). Da el mismo resultado sea cual sea el camino que llegue primero.
 *
 * <p>Orden:
 * <ol>
 *   <li>Fuera de toda transacción: se consulta el pago a la API de MP. De la query o de la notificación solo se toma
 *       el payment_id; estado, monto, moneda y external_reference salen de la API.</li>
 *   <li>Transacción corta: SELECT ... FOR UPDATE sobre la compra (serializa webhook y navegador), validaciones, y
 *       creación de cuenta + evento + marca de procesada + reserva del mail, todo o nada.</li>
 *   <li>Después del commit: el mail. Si falla, se libera la reserva y la próxima confirmación lo reintenta.</li>
 * </ol>
 * Una violación de unicidad del email (otra compra o el superadmin crearon la cuenta en paralelo) deja la transacción
 * de Postgres abortada: NO se captura adentro. Sale, la transacción hace rollback completo, y el incidente se registra
 * en una transacción nueva.
 *
 * <p>Incidentes: pago aprobado que no puede crear nada. Se registran una sola vez por payment_id, con log ERROR y
 * aviso a Sentry que llevan payment_id y external_reference, nunca email ni datos del pagador. Nunca hay reembolso
 * automático.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.checkout.enabled", havingValue = "true")
public class PurchaseConfirmationService {

    public enum Outcome {
        /** Se creó lo que correspondía a la compra. */
        CREATED,
        /** La compra ya estaba procesada con este mismo pago: nada que hacer (idempotente). */
        ALREADY_PROCESSED,
        /** El pago no está aprobado (rechazado, pendiente, en revisión...): no se crea nada. */
        NOT_APPROVED,
        /** Revisión manual: no se creó nada (registrado o ya registrado). */
        INCIDENT,
        /** Mercado Pago no conoce ese payment_id (404). */
        UNKNOWN_PAYMENT
    }

    private static final Pattern PAYMENT_ID = Pattern.compile("^\\d{1,18}$");
    private static final Set<String> REVERSAL_STATUSES = Set.of("refunded", "charged_back");

    private final PendingPurchaseRepository purchases;
    private final PaymentIncidentRepository incidents;
    private final OrganizerRepository organizers;
    private final EventCreationService eventCreationService;
    private final PaymentLookupGateway gateway;
    private final PurchaseConfirmationMailer mailer;
    private final TransactionTemplate tx;
    private final TransactionTemplate newTx;

    public PurchaseConfirmationService(PendingPurchaseRepository purchases, PaymentIncidentRepository incidents,
                                       OrganizerRepository organizers, EventCreationService eventCreationService,
                                       PaymentLookupGateway gateway, PurchaseConfirmationMailer mailer,
                                       PlatformTransactionManager transactionManager) {
        this.purchases = purchases;
        this.incidents = incidents;
        this.organizers = organizers;
        this.eventCreationService = eventCreationService;
        this.gateway = gateway;
        this.mailer = mailer;
        this.tx = new TransactionTemplate(transactionManager);
        this.newTx = new TransactionTemplate(transactionManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public static boolean isValidPaymentId(String raw) {
        return raw != null && PAYMENT_ID.matcher(raw).matches();
    }

    /**
     * @throws IllegalArgumentException si el payment_id no tiene formato de id de MP
     * @throws PaymentGatewayException  si la API de MP no responde (el webhook responde 5xx para que MP reintente)
     */
    public Outcome confirmPayment(String rawPaymentId) {
        if (!isValidPaymentId(rawPaymentId)) {
            throw new IllegalArgumentException("payment_id con formato inválido");
        }
        Optional<PaymentInfo> found = gateway.findPayment(Long.parseLong(rawPaymentId));
        if (found.isEmpty()) {
            log.info("Mercado Pago no conoce el pago payment_id={}", rawPaymentId);
            return Outcome.UNKNOWN_PAYMENT;
        }
        PaymentInfo payment = found.get();
        String paymentId = String.valueOf(payment.id());
        UUID reference = parseReference(payment.externalReference());
        String status = payment.status() == null ? "" : payment.status();

        if (REVERSAL_STATUSES.contains(status)) {
            return handleReversal(payment, paymentId, reference, status);
        }
        if (!"approved".equals(status)) {
            // Con binary_mode solo deberían llegar approved/rejected; pending, in_process o authorized tampoco crean
            // nada (si después se aprueban, llega otra notificación).
            log.info("Pago no aprobado: payment_id={} status={}", paymentId, abbreviate(status));
            if (reference != null) {
                purchases.recordPaymentStatus(reference, abbreviate(status));
            }
            return Outcome.NOT_APPROVED;
        }
        if (reference == null) {
            return tx.execute(s -> incident(paymentId, abbreviate(payment.externalReference()), Reason.UNKNOWN_REFERENCE));
        }

        try {
            return tx.execute(s -> processLocked(reference, payment, paymentId));
        } catch (DataIntegrityViolationException e) {
            // La transacción de creación ya hizo rollback completo. En una nueva: ¿fue el email?
            return tx.execute(s -> recordEmailTaken(reference, paymentId, e));
        }
    }

    private Outcome processLocked(UUID reference, PaymentInfo payment, String paymentId) {
        PendingPurchase purchase = purchases.findByIdForUpdate(reference).orElse(null);
        if (purchase == null) {
            return incident(paymentId, reference.toString(), Reason.UNKNOWN_REFERENCE);
        }
        if (purchase.getProcessedAt() != null) {
            if (paymentId.equals(purchase.getMpPaymentId())) {
                retryEmailIfPending(purchase);
                return Outcome.ALREADY_PROCESSED;
            }
            return incident(paymentId, reference.toString(), Reason.DUPLICATE_PAYMENT);
        }
        if (purchase.getMpPaymentId() != null) {
            // Compra con incidente de "email ya registrado" (es la única que guarda el pago sin procesarse).
            return incident(paymentId, reference.toString(),
                    paymentId.equals(purchase.getMpPaymentId()) ? Reason.EMAIL_ALREADY_REGISTERED : Reason.DUPLICATE_PAYMENT);
        }
        if (!"ARS".equals(payment.currencyId())) {
            return incident(paymentId, reference.toString(), Reason.CURRENCY_MISMATCH);
        }
        if (payment.amount() == null || payment.amount().compareTo(purchase.getAmount()) != 0) {
            return incident(paymentId, reference.toString(), Reason.AMOUNT_MISMATCH);
        }
        if (payment.amountRefunded() != null && payment.amountRefunded().signum() > 0) {
            return incident(paymentId, reference.toString(), Reason.PARTIALLY_REFUNDED);
        }

        boolean repurchase = purchase.getOrganizerId() != null;
        Organizer organizer;
        if (repurchase) {
            organizer = organizers.findById(purchase.getOrganizerId()).orElse(null);
            if (organizer == null) {
                return incident(paymentId, reference.toString(), Reason.ORGANIZER_MISSING);
            }
        } else {
            if (organizers.existsByEmailIgnoreCase(purchase.getEmail())) {
                return emailTaken(purchase, paymentId);
            }
            // saveAndFlush: si otra compra con el mismo email insertó en paralelo, la violación de unicidad sale acá
            // (traducida a DataIntegrityViolationException) y aborta esta transacción entera; se maneja afuera.
            // La cuenta queda usable directamente: el hash es el de la contraseña elegida en /comprar.
            organizer = organizers.saveAndFlush(Organizer.builder()
                    .email(purchase.getEmail())
                    .passwordHash(purchase.getPasswordHash())
                    .build());
        }
        Event event = eventCreationService.createEvent(organizer, purchase.getEventName(), EventOrigin.PAID, null);

        purchase.setProcessedAt(Instant.now());
        purchase.setMpPaymentId(paymentId);
        purchase.setEventId(event.getId());
        purchase.setOrganizerId(organizer.getId());
        purchase.setCreatedOrganizer(!repurchase);
        purchase.setLastPaymentStatus("approved");
        // Minimización: el email y el hash ya viven en organizer.
        purchase.setEmail(null);
        purchase.setPasswordHash(null);
        purchase.setConfirmationEmailSentAt(Instant.now());
        purchases.saveAndFlush(purchase);

        String to = organizer.getEmail();
        String eventName = event.getName();
        afterCommit(() -> sendConfirmationEmail(reference, to, eventName, repurchase));
        log.info("Compra confirmada: external_reference={} payment_id={} ({})",
                reference, paymentId, repurchase ? "recompra" : "cuenta nueva");
        return Outcome.CREATED;
    }

    /** Transacción nueva, después del rollback de la de creación. Solo es incidente si de verdad el email está tomado. */
    private Outcome recordEmailTaken(UUID reference, String paymentId, DataIntegrityViolationException cause) {
        PendingPurchase purchase = purchases.findByIdForUpdate(reference).orElseThrow(() -> cause);
        if (purchase.getProcessedAt() != null) {
            return paymentId.equals(purchase.getMpPaymentId())
                    ? Outcome.ALREADY_PROCESSED
                    : incident(paymentId, reference.toString(), Reason.DUPLICATE_PAYMENT);
        }
        if (purchase.getEmail() == null || !organizers.existsByEmailIgnoreCase(purchase.getEmail())) {
            throw cause; // otra violación: que el webhook responda 5xx y MP reintente
        }
        return emailTaken(purchase, paymentId);
    }

    /**
     * No se crea nada ni se toca la cuenta existente. La compra guarda el pago (sigue sin procesar, con email y hash
     * para la revisión manual): eso la protege del descarte y hace que una notificación repetida no la reintente.
     */
    private Outcome emailTaken(PendingPurchase purchase, String paymentId) {
        if (purchase.getMpPaymentId() == null) {
            purchase.setMpPaymentId(paymentId);
            purchases.saveAndFlush(purchase);
        }
        return incident(paymentId, purchase.getId().toString(), Reason.EMAIL_ALREADY_REGISTERED);
    }

    private Outcome handleReversal(PaymentInfo payment, String paymentId, UUID reference, String status) {
        if (reference == null) {
            log.info("Pago {} sin compra asociada: payment_id={}", status, paymentId);
            return Outcome.NOT_APPROVED;
        }
        return tx.execute(s -> {
            PendingPurchase purchase = purchases.findByIdForUpdate(reference).orElse(null);
            if (purchase != null && purchase.getProcessedAt() != null && paymentId.equals(purchase.getMpPaymentId())) {
                return incident(paymentId, reference.toString(), "refunded".equals(status) ? Reason.REFUNDED : Reason.CHARGED_BACK);
            }
            log.info("Pago {} de una compra no procesada: payment_id={}", status, paymentId);
            if (purchase != null && purchase.getProcessedAt() == null) {
                purchase.setLastPaymentStatus(status);
            }
            return Outcome.NOT_APPROVED;
        });
    }

    /** Dentro de una transacción. Avisa (log ERROR + Sentry) solo si lo registró ahora, y recién después del commit. */
    private Outcome incident(String paymentId, String externalReference, Reason reason) {
        int inserted = incidents.insertIfAbsent(UUID.randomUUID(), paymentId, externalReference, reason.name());
        if (inserted == 1) {
            afterCommit(() -> alert(paymentId, externalReference, reason));
        } else {
            log.info("Incidente ya registrado para payment_id={}", paymentId);
        }
        return Outcome.INCIDENT;
    }

    private void alert(String paymentId, String externalReference, Reason reason) {
        String message = "Incidente de pago (" + reason + "): payment_id=" + paymentId + " external_reference=" + externalReference
                + ". No se creó nada: revisión manual.";
        log.error(message);
        Sentry.captureMessage(message, SentryLevel.ERROR);
    }

    /** La compra ya está procesada pero el mail no salió (falló el envío): se vuelve a reservar y a intentar. */
    private void retryEmailIfPending(PendingPurchase purchase) {
        if (purchase.getConfirmationEmailSentAt() != null || purchase.getOrganizerId() == null) {
            return;
        }
        Organizer organizer = organizers.findById(purchase.getOrganizerId()).orElse(null);
        if (organizer == null) {
            return;
        }
        purchase.setConfirmationEmailSentAt(Instant.now());
        purchases.saveAndFlush(purchase);
        UUID reference = purchase.getId();
        String to = organizer.getEmail();
        String eventName = purchase.getEventName();
        boolean repurchase = purchase.isRepurchase();
        afterCommit(() -> sendConfirmationEmail(reference, to, eventName, repurchase));
    }

    private void sendConfirmationEmail(UUID reference, String to, String eventName, boolean repurchase) {
        try {
            mailer.sendEventReady(to, eventName, repurchase, reference);
        } catch (RuntimeException e) {
            log.error("No se pudo enviar el mail de confirmación (external_reference={}): {}. Se reintenta en la próxima confirmación.",
                    reference, e.getClass().getSimpleName());
            Sentry.captureMessage("Falló el mail de confirmación de compra: external_reference=" + reference, SentryLevel.ERROR);
            try {
                // afterCommit: la transacción original ya terminó, hace falta una nueva (REQUIRES_NEW) para que se confirme.
                newTx.executeWithoutResult(s -> purchases.clearEmailClaim(reference));
            } catch (RuntimeException clearFailed) {
                log.error("No se pudo liberar la reserva del mail (external_reference={}): {}", reference, clearFailed.getClass().getSimpleName());
            }
        }
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    static UUID parseReference(String raw) {
        if (raw == null || raw.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.replaceAll("[^A-Za-z0-9_.:-]", "?");
        return clean.length() > 20 ? clean.substring(0, 20) : clean;
    }
}
