package com.tuapp.eventfoto.event;

/**
 * Por qué puerta entra la request: el panel del organizador (se resuelve por {slug}) o el link
 * de moderador (se resuelve por {token}). Cada {@link EventAccessPolicy} concede solo en su ámbito.
 */
public enum AccessScope {
    PANEL("slug"),
    MODERATOR("token");

    private final String locatorVariable;

    AccessScope(String locatorVariable) {
        this.locatorVariable = locatorVariable;
    }

    /** Variable de ruta que identifica el evento en este ámbito. */
    public String locatorVariable() {
        return locatorVariable;
    }

    /** El ámbito de una ruta mapeada, según su prefijo. */
    public static AccessScope ofPattern(String pattern) {
        if (pattern != null && (pattern.startsWith("/moderar") || pattern.startsWith("/api/v1/moderate"))) {
            return MODERATOR;
        }
        return PANEL;
    }
}
