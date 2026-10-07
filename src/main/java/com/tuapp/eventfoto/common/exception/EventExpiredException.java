package com.tuapp.eventfoto.common.exception;

/** El álbum del evento venció (ver UploadWindow#isExpired): 410 con error = {@link #CODE}, o la pantalla de no disponible. */
public class EventExpiredException extends RuntimeException {

    public static final String CODE = "EVENT_EXPIRED";

    public EventExpiredException() {
        super("Este álbum ya no está disponible.");
    }
}
