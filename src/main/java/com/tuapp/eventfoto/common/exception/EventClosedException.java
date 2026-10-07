package com.tuapp.eventfoto.common.exception;

/**
 * Escritura de invitado fuera de la ventana (ver UploadWindow#assertGuestCanWrite): 400 con el código en "error"
 * (UPLOAD_NOT_OPEN, UPLOAD_CLOSED, EVENT_NOT_READY o EVENT_CLOSED) y un mensaje para mostrarle al invitado.
 */
public class EventClosedException extends RuntimeException {

    private final String code;

    public EventClosedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
