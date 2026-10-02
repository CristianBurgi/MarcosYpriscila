package com.tuapp.eventfoto.common.exception;

/**
 * El evento llegó al tope total de fotos (app.event.max-photos). Es un tope de seguridad, no un límite por
 * invitado: se responde 409 (el estado del álbum impide la subida), distinto del 403 de cupo del invitado.
 */
public class EventPhotoLimitReachedException extends RuntimeException {

    public EventPhotoLimitReachedException(String message) {
        super(message);
    }
}
