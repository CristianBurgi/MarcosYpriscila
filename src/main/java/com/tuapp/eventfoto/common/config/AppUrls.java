package com.tuapp.eventfoto.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * Única fuente de toda URL absoluta que genera la app (QR, links del panel, etc.).
 *
 * Se configura con la variable de entorno APP_BASE_URL (propiedad {@code app.base-url}).
 * Nunca se deriva del host de la request ni se escribe un dominio a mano en el código:
 * el día de la mudanza a eventfoto.com.ar alcanza con cambiar APP_BASE_URL en Railway
 * (ver README, "Mudanza de dominio").
 *
 * Falla el arranque si el valor no es una URL http(s) absoluta, o si en producción
 * (app.storage.mode=r2) apunta a localhost: un QR impreso con localhost es inservible.
 */
@Slf4j
@Component
public class AppUrls {

    private final String baseUrl;

    public AppUrls(@Value("${app.base-url:}") String rawBaseUrl,
                   @Value("${app.storage.mode:local}") String storageMode) {
        this.baseUrl = normalizeAndValidate(rawBaseUrl, storageMode);
        log.info("APP_BASE_URL resuelta: '{}'", baseUrl);
    }

    static String normalizeAndValidate(String rawBaseUrl, String storageMode) {
        String value = rawBaseUrl == null ? "" : rawBaseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty()) {
            throw new IllegalStateException("APP_BASE_URL (app.base-url) no está seteada. Ejemplo: https://midominio.com");
        }

        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("APP_BASE_URL no es una URL válida: '" + value + "'", e);
        }
        String scheme = uri.getScheme();
        if (uri.getHost() == null || scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalStateException("APP_BASE_URL debe ser una URL http(s) absoluta: '" + value + "'");
        }

        boolean isLocalhost = uri.getHost().equalsIgnoreCase("localhost") || uri.getHost().startsWith("127.");
        if (isLocalhost && "r2".equalsIgnoreCase(storageMode == null ? "" : storageMode.trim())) {
            throw new IllegalStateException("APP_BASE_URL apunta a localhost ('" + value + "') con app.storage.mode=r2 "
                    + "(producción). Seteá APP_BASE_URL con el dominio público; si no, los QR apuntarían a localhost.");
        }
        return value;
    }

    /** URL base sin barra final, ej. {@code https://eventfoto.com.ar}. */
    public String baseUrl() {
        return baseUrl;
    }

    /** URL absoluta a la que apunta el QR de un evento (menú del invitado). */
    public String guestMenuUrl(String slug) {
        return baseUrl + "/menu.html?slug=" + slug;
    }
}
