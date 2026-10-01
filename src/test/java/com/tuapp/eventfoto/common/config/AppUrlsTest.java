package com.tuapp.eventfoto.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppUrlsTest {

    @Test
    @DisplayName("Normaliza APP_BASE_URL (sin barra final) y arma la URL del menú del invitado")
    void normalizesAndBuildsGuestMenuUrl() {
        AppUrls urls = new AppUrls("  https://eventfoto.com.ar/  ", "r2");

        assertThat(urls.baseUrl()).isEqualTo("https://eventfoto.com.ar");
        assertThat(urls.guestMenuUrl("evento-demo-k7m2xq9p")).isEqualTo("https://eventfoto.com.ar/e/evento-demo-k7m2xq9p");
    }

    @Test
    @DisplayName("Falla el arranque si APP_BASE_URL está vacía o no es una URL http(s) absoluta")
    void rejectsMissingOrInvalidValues() {
        assertThatThrownBy(() -> new AppUrls("", "local")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AppUrls("eventfoto.com.ar", "local")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AppUrls("ftp://eventfoto.com.ar", "local")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("En producción (storage r2) no acepta localhost: un QR impreso con localhost es inservible")
    void rejectsLocalhostInProduction() {
        assertThatThrownBy(() -> new AppUrls("http://localhost:8080", "r2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("localhost");
        assertThat(new AppUrls("http://localhost:8080", "local").baseUrl()).isEqualTo("http://localhost:8080");
    }

    @Test
    @DisplayName("Rechaza valores de ejemplo (el placeholder que tenía Railway el 24/09), en cualquier modo")
    void rejectsPlaceholderValues() {
        assertThatThrownBy(() -> new AppUrls("https://tu-boda-produccion.up.railway.app", "r2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tu-boda");
        assertThatThrownBy(() -> new AppUrls("https://tu-boda-produccion.up.railway.app", "local"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AppUrls("https://app.example.com", "r2")).isInstanceOf(IllegalStateException.class);
        assertThat(new AppUrls("https://eventfoto-demo.up.railway.app", "r2").baseUrl())
                .isEqualTo("https://eventfoto-demo.up.railway.app");
    }
}
