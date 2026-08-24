package com.tuapp.eventfoto.common.exception;

/**
 * Se lanza cuando un invitado (identificado por su guestToken anónimo) ya
 * alcanzó el máximo de fotos permitido para un evento.
 */
public class GuestQuotaExceededException extends RuntimeException {

    public GuestQuotaExceededException(String message) {
        super(message);
    }
}
