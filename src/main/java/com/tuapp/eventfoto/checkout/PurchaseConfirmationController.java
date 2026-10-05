package com.tuapp.eventfoto.checkout;

import com.mercadopago.exceptions.MPInvalidWebhookSignatureException;
import com.mercadopago.webhook.WebhookSignatureValidator;
import com.tuapp.eventfoto.common.config.ClientIpResolver;
import com.tuapp.eventfoto.common.config.RateLimiterService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * Confirmación del pago: webhook de Mercado Pago y los dos endpoints de la página de retorno. Solo existen con
 * app.checkout.enabled=true. Ninguno emite cookies ni loguea cuerpos, headers de firma o parámetros de la URL.
 */
@Slf4j
@RestController
@ConditionalOnProperty(name = "app.checkout.enabled", havingValue = "true")
public class PurchaseConfirmationController {

    public record ConfirmRequestDTO(String paymentId) {
    }

    public record StatusRequestDTO(String externalReference) {
    }

    public record StatusResponseDTO(String state, String eventName, boolean repurchase) {
    }

    private final PurchaseConfirmationService confirmationService;
    private final PurchaseStatusService statusService;
    private final CheckoutSettings settings;
    private final RateLimiterService rateLimiterService;
    private final ClientIpResolver clientIpResolver;
    private final long unknownPaymentRetryMs;

    public PurchaseConfirmationController(PurchaseConfirmationService confirmationService, PurchaseStatusService statusService,
                                          CheckoutSettings settings, RateLimiterService rateLimiterService,
                                          ClientIpResolver clientIpResolver,
                                          @Value("${app.checkout.webhook-unknown-payment-retry-ms:2000}") long unknownPaymentRetryMs) {
        this.confirmationService = confirmationService;
        this.statusService = statusService;
        this.settings = settings;
        this.rateLimiterService = rateLimiterService;
        this.clientIpResolver = clientIpResolver;
        this.unknownPaymentRetryMs = unknownPaymentRetryMs;
    }

    /**
     * Webhook de MP. La firma se valida ANTES de cualquier otra cosa (un request sin firma válida no genera tráfico
     * hacia MP). El cuerpo no se lee: el id sale de {@code data.id} en la query, que es lo que cubre la firma.
     * <ul>
     *   <li>401: firma ausente o inválida (sin consultar nada).</li>
     *   <li>200: procesado, nada que hacer, tipo ignorado, pago no aprobado, pago desconocido o incidente (registrado
     *       o ya registrado): MP no reintenta.</li>
     *   <li>503: la API de MP o la base no responden: MP reintenta.</li>
     * </ul>
     */
    @PostMapping("/api/v1/checkout/webhook")
    public ResponseEntity<Void> webhook(@RequestParam(name = "data.id", required = false) String dataId,
                                        @RequestParam(name = "type", required = false) String type,
                                        @RequestHeader(name = "x-signature", required = false) String signature,
                                        @RequestHeader(name = "x-request-id", required = false) String requestId) {
        String normalizedId = dataId == null ? null : dataId.trim().toLowerCase(Locale.ROOT);
        try {
            // Sin ventana de tolerancia sobre ts: un replay solo vuelve a consultar un pago real (idempotente) y una
            // ventana corta podría rechazar reintentos legítimos de MP. Un ts manipulado igual rompe el HMAC.
            WebhookSignatureValidator.validate(signature, requestId, normalizedId, settings.webhookSecret());
        } catch (MPInvalidWebhookSignatureException e) {
            log.warn("Notificación de Mercado Pago rechazada: firma inválida ({})", e.getReason());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (RuntimeException e) {
            log.warn("Notificación de Mercado Pago rechazada: firma ilegible ({})", e.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (!"payment".equals(type)) {
            log.info("Notificación de Mercado Pago ignorada (tipo no es payment)");
            return ResponseEntity.ok().build();
        }
        if (!PurchaseConfirmationService.isValidPaymentId(normalizedId)) {
            return ResponseEntity.badRequest().build();
        }

        try {
            PurchaseConfirmationService.Outcome outcome = confirmationService.confirmPayment(normalizedId);
            if (outcome == PurchaseConfirmationService.Outcome.UNKNOWN_PAYMENT) {
                // A veces MP notifica antes de que el pago sea consultable: un solo reintento corto.
                sleepQuietly(unknownPaymentRetryMs);
                outcome = confirmationService.confirmPayment(normalizedId);
            }
            log.info("Notificación de pago procesada: payment_id={} resultado={}", normalizedId, outcome);
            return ResponseEntity.ok().build();
        } catch (PaymentGatewayException | DataAccessException | TransactionException e) {
            log.warn("Notificación de pago payment_id={} sin procesar ({}): MP va a reintentar", normalizedId, e.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    /**
     * La página de retorno pide confirmar el pago con el payment_id que trajo la URL. No devuelve el estado: por
     * payment_id (numérico) se podrían enumerar los estados de los pagos de la cuenta. El estado se consulta aparte,
     * por la referencia de la compra. Si MP no responde, igual 204: la página sigue mostrando "confirmando".
     */
    @PostMapping("/api/v1/checkout/confirm")
    public ResponseEntity<Void> confirm(@RequestBody ConfirmRequestDTO request, HttpServletRequest httpRequest) {
        rateLimiterService.checkCheckoutConfirmRateLimit(clientIpResolver.resolve(httpRequest));
        if (request == null || !PurchaseConfirmationService.isValidPaymentId(request.paymentId())) {
            return ResponseEntity.badRequest().build();
        }
        try {
            confirmationService.confirmPayment(request.paymentId());
        } catch (PaymentGatewayException | DataAccessException | TransactionException e) {
            log.info("Confirmación desde el retorno pendiente: payment_id={} ({})", request.paymentId(), e.getClass().getSimpleName());
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/checkout/status")
    public ResponseEntity<StatusResponseDTO> status(@RequestBody StatusRequestDTO request, HttpServletRequest httpRequest) {
        rateLimiterService.checkCheckoutStatusRateLimit(clientIpResolver.resolve(httpRequest));
        PurchaseStatusService.PurchaseStatus status = statusService.statusOf(request == null ? null : request.externalReference());
        return ResponseEntity.ok(new StatusResponseDTO(status.state().name(), status.eventName(), status.repurchase()));
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
