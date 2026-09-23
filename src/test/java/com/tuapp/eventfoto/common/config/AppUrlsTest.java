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
        assertThat(urls.guestMenuUrl("marcos-y-priscila")).isEqualTo("https://eventfoto.com.ar/menu.html?slug=marcos-y-priscila");
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
}
