package com.tuapp.eventfoto.checkout;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * Estado de una compra para la página de retorno. Devuelve solo el estado, si es una recompra (para el texto y el
 * link) y, si el evento está creado, su nombre: nada que sirva para entrar a la cuenta (ni email, ni slug, ni ids).
 * Una referencia inexistente responde PENDING, igual que una real todavía sin confirmar.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.checkout.enabled", havingValue = "true")
public class PurchaseStatusService {

    public enum State { PENDING, CREATED, REJECTED, INCIDENT }

    public record PurchaseStatus(State state, String eventName, boolean repurchase) {
    }

    private static final Set<String> REJECTED_STATUSES = Set.of("rejected", "cancelled");

    private final PendingPurchaseRepository purchases;
    private final PaymentIncidentRepository incidents;

    @Transactional(readOnly = true)
    public PurchaseStatus statusOf(String rawReference) {
        UUID reference = PurchaseConfirmationService.parseReference(rawReference);
        if (reference == null) {
            return new PurchaseStatus(State.PENDING, null, false);
        }
        PendingPurchase purchase = purchases.findById(reference).orElse(null);
        boolean repurchase = purchase != null && purchase.isRepurchase();
        if (purchase != null && purchase.getProcessedAt() != null) {
            return new PurchaseStatus(State.CREATED, purchase.getEventName(), repurchase);
        }
        if (incidents.existsByExternalReferenceAndResolvedAtIsNull(reference.toString())) {
            return new PurchaseStatus(State.INCIDENT, null, repurchase);
        }
        if (purchase != null && purchase.getLastPaymentStatus() != null && REJECTED_STATUSES.contains(purchase.getLastPaymentStatus())) {
            return new PurchaseStatus(State.REJECTED, null, repurchase);
        }
        return new PurchaseStatus(State.PENDING, null, repurchase);
    }
}
