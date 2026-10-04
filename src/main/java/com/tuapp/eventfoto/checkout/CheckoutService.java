package com.tuapp.eventfoto.checkout;

import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.organizer.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Registra la compra pendiente y pide la preferencia de pago. No crea cuentas ni eventos (eso es la confirmación del
 * pago, 9.4). Los métodos NO son transaccionales a propósito: el hash de BCrypt y la llamada HTTP a MP no tienen que
 * retener una conexión del pool. Orden: (1) se guarda la compra y se confirma, (2) se llama a MP, (3) se anota el id de
 * la preferencia. Al revés, un pago podría llegar sin registro de la compra. Si MP falla, la compra se borra enseguida.
 *
 * Logs: solo el external_reference (el id de la compra), nunca junto al email; jamás contraseña, hash ni token.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.checkout.enabled", havingValue = "true")
public class CheckoutService {

    /** Vigencia de la preferencia en MP: pasado ese momento nadie puede iniciar un pago nuevo. */
    static final Duration PREFERENCE_TTL = Duration.ofHours(24);
    /** Margen sobre el vencimiento antes de descartar: un pago iniciado a último momento todavía puede aprobarse. */
    static final Duration DISCARD_MARGIN = Duration.ofHours(48);
    /** Una compra sin preferencia más vieja que esto es un intento que falló (el pedido a MP dura segundos). */
    static final Duration ORPHAN_GRACE = Duration.ofMinutes(15);

    static final int MAX_EVENT_NAME = 100;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final PendingPurchaseRepository purchases;
    private final OrganizerRepository organizers;
    private final PasswordEncoder passwordEncoder;
    private final PaymentPreferenceGateway gateway;
    private final CheckoutSettings settings;

    /** Cliente nuevo. Devuelve la URL de pago de Mercado Pago. */
    public String startNewPurchase(String rawEmail, String password, String passwordConfirmation, String rawEventName) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase();
        String eventName = rawEventName == null ? "" : rawEventName.trim();

        List<String> problems = new ArrayList<>();
        if (email.isEmpty() || email.length() > 255 || !EMAIL.matcher(email).matches()) {
            problems.add("Ingresá un email válido");
        }
        PasswordPolicy.violation(password, email).ifPresent(problems::add);
        if (password != null && !password.equals(passwordConfirmation)) {
            problems.add("Las contraseñas no coinciden");
        }
        eventNameProblem(eventName).ifPresent(problems::add);
        if (!problems.isEmpty()) {
            throw new InvalidCheckoutRequestException(String.join("; ", problems));
        }

        if (organizers.existsByEmailIgnoreCase(email)) {
            throw new EmailAlreadyRegisteredException();
        }

        PendingPurchase purchase = PendingPurchase.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .eventName(eventName)
                .build();
        return register(purchase);
    }

    /** Cliente existente: el organizerId viene del principal autenticado, nunca del cuerpo del pedido. */
    public String startRepurchase(UUID organizerId, String rawEventName) {
        String eventName = rawEventName == null ? "" : rawEventName.trim();
        eventNameProblem(eventName).ifPresent(problem -> {
            throw new InvalidCheckoutRequestException(problem);
        });
        PendingPurchase purchase = PendingPurchase.builder()
                .organizerId(organizerId)
                .eventName(eventName)
                .build();
        return register(purchase);
    }

    private static java.util.Optional<String> eventNameProblem(String eventName) {
        if (eventName.isEmpty()) {
            return java.util.Optional.of("Ingresá el nombre del evento");
        }
        if (eventName.length() > MAX_EVENT_NAME) {
            return java.util.Optional.of("El nombre del evento es demasiado largo (máximo " + MAX_EVENT_NAME + " caracteres)");
        }
        return java.util.Optional.empty();
    }

    private String register(PendingPurchase purchase) {
        // El monto sale siempre de la configuración: nada que venga del cliente llega hasta acá.
        purchase.setAmount(settings.priceArs());
        purchase.setCreatedAt(Instant.now());
        PendingPurchase saved = purchases.save(purchase);
        try {
            PaymentPreferenceGateway.Preference preference =
                    gateway.createPreference(saved.getId(), saved.getAmount(), saved.getCreatedAt().plus(PREFERENCE_TTL));
            purchases.setPreferenceId(saved.getId(), preference.id());
            log.info("Compra pendiente creada: external_reference={}", saved.getId());
            return preference.initPoint();
        } catch (RuntimeException e) {
            discardQuietly(saved.getId());
            throw e;
        }
    }

    private void discardQuietly(UUID id) {
        try {
            purchases.deleteById(id);
            log.info("Compra pendiente descartada porque no se pudo crear la preferencia: external_reference={}", id);
        } catch (RuntimeException e) {
            log.warn("No se pudo borrar la compra pendiente external_reference={}; la limpieza periódica se encarga ({})",
                    id, e.getClass().getSimpleName());
        }
    }

    /** Ver {@link PendingPurchaseRepository#discard}. Devuelve cuántas compras se descartaron. */
    public int discardStale(Instant now) {
        return purchases.discard(now.minus(ORPHAN_GRACE), now.minus(PREFERENCE_TTL).minus(DISCARD_MARGIN));
    }
}
