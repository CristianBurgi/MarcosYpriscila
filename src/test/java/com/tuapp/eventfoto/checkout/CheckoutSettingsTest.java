package com.tuapp.eventfoto.checkout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckoutSettingsTest {

    @Test
    @DisplayName("Apagado: no exige ni valida nada (ni token ni precio)")
    void disabledNeedsNothing() {
        CheckoutSettings settings = new CheckoutSettings(false, "", "");
        assertThat(settings.enabled()).isFalse();
        new CheckoutSettings(false, "no-es-un-numero", "<placeholder>");
    }

    @Test
    @DisplayName("Prendido y sin access token -> falla el arranque, nombrando la variable")
    void enabledWithoutTokenFails() {
        assertThatThrownBy(() -> new CheckoutSettings(true, "50000", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MP_ACCESS_TOKEN");
        assertThatThrownBy(() -> new CheckoutSettings(true, "50000", "   "))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MP_ACCESS_TOKEN");
        assertThatThrownBy(() -> new CheckoutSettings(true, "50000", "tu_access_token_de_mp"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("placeholder");
    }

    @Test
    @DisplayName("Prendido con precio <= 0 o que no es número -> falla el arranque; el mensaje no repite el token")
    void enabledWithInvalidPriceFails() {
        for (String price : new String[]{"0", "-5", "", "gratis", "0.00", "10.123"}) {
            assertThatThrownBy(() -> new CheckoutSettings(true, price, "TEST-abc-secreto"))
                    .as("precio '%s'", price)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CHECKOUT_PRICE_ARS")
                    .hasMessageNotContaining("TEST-abc-secreto");
        }
    }

    @Test
    @DisplayName("Prendido y bien configurado: el precio sale de la configuración con 2 decimales")
    void enabledAndValid() {
        CheckoutSettings settings = new CheckoutSettings(true, "50000", "TEST-abc");
        assertThat(settings.priceArs()).isEqualByComparingTo(new BigDecimal("50000"));
        assertThat(settings.accessToken()).isEqualTo("TEST-abc");
    }

    @Test
    @DisplayName("Contexto de Spring: con true y sin credenciales no arranca; con false sí; con true y credenciales sí")
    void springContextStartupBehaviour() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(CheckoutSettings.class);

        runner.withPropertyValues("app.checkout.enabled=true", "app.checkout.price-ars=50000")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
                    assertThat(rootMessage(context.getStartupFailure())).contains("MP_ACCESS_TOKEN");
                });
        runner.withPropertyValues("app.checkout.enabled=false").run(context -> assertThat(context).hasNotFailed());
        runner.run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("app.checkout.enabled=true", "app.checkout.price-ars=50000", "app.checkout.mp-access-token=TEST-abc")
                .run(context -> assertThat(context).hasNotFailed());
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage();
    }
}
