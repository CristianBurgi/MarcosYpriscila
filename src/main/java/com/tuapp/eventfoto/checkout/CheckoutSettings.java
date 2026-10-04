package com.tuapp.eventfoto.checkout;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Configuración del checkout. Con {@code app.checkout.enabled=false} (default) no se valida ni se exige nada: la app
 * arranca sin credenciales de MP. Con true, falla el arranque (mismo criterio que R2 y ProductionSecretsValidator) si
 * falta el access token, parece un placeholder, o el precio no es un número mayor que cero. Los mensajes nombran la
 * VARIABLE, nunca el valor.
 */
@Slf4j
@Component
public class CheckoutSettings {

    private static final List<String> PLACEHOLDER_MARKERS = List.of("placeholder", "dummy", "<", "tu_", "xxx", "changeme");

    private final boolean enabled;
    private final BigDecimal priceArs;
    private final String accessToken;

    public CheckoutSettings(@Value("${app.checkout.enabled:false}") boolean enabled,
                            @Value("${app.checkout.price-ars:}") String rawPrice,
                            @Value("${app.checkout.mp-access-token:}") String accessToken) {
        this.enabled = enabled;
        if (!enabled) {
            this.priceArs = null;
            this.accessToken = null;
            log.info("app.checkout.enabled=false: checkout con Mercado Pago apagado (sin rutas, sin credenciales requeridas).");
            return;
        }
        this.priceArs = parsePrice(rawPrice);
        this.accessToken = validateToken(accessToken);
        log.info("Checkout con Mercado Pago habilitado (precio: {} ARS).", priceArs.toPlainString());
    }

    private static BigDecimal parsePrice(String raw) {
        String value = raw == null ? "" : raw.trim();
        try {
            BigDecimal price = new BigDecimal(value);
            if (price.signum() > 0) {
                return price.setScale(2, RoundingMode.UNNECESSARY);
            }
        } catch (NumberFormatException | ArithmeticException ignored) {
            // cae al mensaje de abajo
        }
        throw new IllegalStateException("app.checkout.enabled=true pero CHECKOUT_PRICE_ARS (app.checkout.price-ars) "
                + "tiene que ser un número mayor que cero, con hasta 2 decimales.");
    }

    private static String validateToken(String raw) {
        String value = raw == null ? "" : raw.trim();
        String lower = value.toLowerCase();
        boolean placeholder = PLACEHOLDER_MARKERS.stream().anyMatch(lower::contains);
        if (value.isEmpty() || placeholder) {
            throw new IllegalStateException("app.checkout.enabled=true pero MP_ACCESS_TOKEN (app.checkout.mp-access-token) "
                    + (value.isEmpty() ? "no está seteada" : "parece un valor placeholder")
                    + ". Seteá el access token de Mercado Pago (de PRUEBA hasta la L6) o dejá CHECKOUT_ENABLED en false.");
        }
        return value;
    }

    public boolean enabled() {
        return enabled;
    }

    public BigDecimal priceArs() {
        return priceArs;
    }

    public String accessToken() {
        return accessToken;
    }
}
