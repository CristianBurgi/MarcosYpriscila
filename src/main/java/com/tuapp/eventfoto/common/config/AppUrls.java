package com.tuapp.eventfoto.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;

/**
 * Única fuente de toda URL absoluta que genera la app (QR, links del panel, etc.).
 *
 * Se configura con la variable de entorno APP_BASE_URL (propiedad {@code app.base-url}).
 * Nunca se deriva del host de la request ni se escribe un dominio a mano en el código:
 * el día de la mudanza a eventfoto.com.ar alcanza con cambiar APP_BASE_URL en Railway
 * (ver README, "Mudanza de dominio").
 *
 * Falla el arranque si el valor no es una URL http(s) absoluta, si parece un valor de
 * ejemplo (tu-boda, example, placeholder...), o si en producción (app.storage.mode=r2)
 * apunta a localhost: un QR impreso con cualquiera de esos es inservible.
 */
@Slf4j
@Component
public class AppUrls {

    /** Fragmentos de host que delatan un valor de ejemplo copiado de la documentación. */
    static final List<String> PLACEHOLDER_HOST_MARKERS = List.of("tu-boda", "tu-dominio", "tudominio", "example", "placeholder");

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

        String host = uri.getHost().toLowerCase();
        for (String marker : PLACEHOLDER_HOST_MARKERS) {
            if (host.contains(marker)) {
                // Fase 9.0 (A2): Railway tenía APP_BASE_URL=https://tu-boda-produccion.up.railway.app
                // y el QR del panel apuntaba a un dominio inexistente.
                throw new IllegalStateException("APP_BASE_URL parece un valor de ejemplo ('" + value + "', contiene '"
                        + marker + "'). Seteá el dominio público real de la app.");
            }
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

    /** URL absoluta a la que apunta el QR de un evento (menú del invitado). Formato estable: va impreso en los QR de las tarjetas de mesa. */
    public String guestMenuUrl(String slug) {
        return baseUrl + "/e/" + slug;
    }

    /** A donde vuelve Mercado Pago después del pago (back_urls). Trae el external_reference en la query: no loguearla. */
    public String checkoutReturnUrl() {
        return baseUrl + "/compra/retorno";
    }

    /** URL absoluta del link de moderador. Contiene la credencial: no loguearla. */
    public String moderatorUrl(String moderatorToken) {
        return baseUrl + "/moderar/" + moderatorToken;
    }
}
