package com.tuapp.eventfoto.common.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailConfigurationValidatorTest {

    @Test
    @DisplayName("Producción con MAIL_PROVIDER=log, sin RESEND_API_KEY o sin MAIL_REPLY_TO: no arranca y nombra cada variable")
    void productionRequiresRealMail() {
        assertThatThrownBy(() -> new MailConfigurationValidator("r2", "log", "", "").validateOnStartup())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_PROVIDER").hasMessageContaining("RESEND_API_KEY").hasMessageContaining("MAIL_REPLY_TO");

        assertThat(new MailConfigurationValidator("r2", "resend", "re_secreta", "").findProblems())
                .singleElement().asString().contains("MAIL_REPLY_TO");
    }

    @Test
    @DisplayName("El mensaje nunca incluye los valores")
    void messageNeverIncludesValues() {
        assertThatThrownBy(() -> new MailConfigurationValidator("r2", "log", "re_secreta_123", "").validateOnStartup())
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("re_secreta_123"));
    }

    @Test
    @DisplayName("Producción completa arranca; en desarrollo (modo local) no se exige nada")
    void validConfigurationsStart() {
        assertThatCode(() -> new MailConfigurationValidator("r2", "resend", "re_x", "respuestas@test.com").validateOnStartup())
                .doesNotThrowAnyException();
        assertThatCode(() -> new MailConfigurationValidator("local", "log", "", "").validateOnStartup())
                .doesNotThrowAnyException();
    }
}
