package com.tuapp.eventfoto.common.moderation;

public interface ContentModerationService {
    /**
     * Verifica si el texto enviado por el usuario es permitido (libre de lenguaje ofensivo/bloqueado).
     *
     * @param text Texto a analizar
     * @return true si el texto es apto para publicarse; false si contiene palabras bloqueadas.
     */
    boolean isAllowed(String text);
}
