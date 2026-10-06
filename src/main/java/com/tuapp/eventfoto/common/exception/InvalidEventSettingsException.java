package com.tuapp.eventfoto.common.exception;

/** Fecha, color o imagen del evento inválidos (o la fecha ya no se puede cambiar): 400 con un mensaje para mostrar. */
public class InvalidEventSettingsException extends RuntimeException {

    public InvalidEventSettingsException(String message) {
        super(message);
    }
}
