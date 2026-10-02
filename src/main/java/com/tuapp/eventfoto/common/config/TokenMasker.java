package com.tuapp.eventfoto.common.config;

import java.util.regex.Pattern;

/**
 * Oculta el token de moderador de cualquier texto que vaya a un log, a una respuesta de error o a Sentry.
 *
 * El token viaja en la ruta (/moderar/{token}, /api/v1/moderate/{token}/...), así que cualquier lugar que
 * escriba la URI de la request lo filtraría. Se enmascara por posición (lo que sigue a esos prefijos) y,
 * como red de seguridad, cualquier cadena base64url de 43 caracteres (el formato del token) en un texto
 * libre, como el mensaje de una excepción de Spring que incluye el path.
 */
public final class TokenMasker {

    public static final String PLACEHOLDER = "{token}";

    private static final Pattern BY_ROUTE = Pattern.compile("(/moderar/|/api/v1/moderate/)[^/?#\s\"']+");
    private static final Pattern BY_SHAPE = Pattern.compile("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])");

    private TokenMasker() {
    }

    /** Devuelve el texto sin ningún token. {@code null} se devuelve como {@code null}. */
    public static String mask(String text) {
        if (text == null) {
            return null;
        }
        String masked = BY_ROUTE.matcher(text).replaceAll("$1" + PLACEHOLDER);
        return BY_SHAPE.matcher(masked).replaceAll(PLACEHOLDER);
    }

    /** Cierto si la ruta pertenece al lado del moderador. */
    public static boolean isModeratorPath(String path) {
        return path != null && (path.startsWith("/moderar/") || path.startsWith("/api/v1/moderate/"));
    }
}
