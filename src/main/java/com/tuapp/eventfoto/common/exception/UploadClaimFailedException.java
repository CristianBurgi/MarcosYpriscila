package com.tuapp.eventfoto.common.exception;

import lombok.Getter;

/**
 * El ganador de la carrera por esta upload_key ya terminó en un rechazo definitivo
 * (archivo inválido, cupo agotado) antes de que esta llamada duplicada llegara. Se le
 * reconstruye al perdedor el MISMO status y mensaje que vio el ganador, en vez de un
 * 503 "procesando" que lo haría reintentar para siempre sin salida (Fase 9.0 - Bloque C).
 */
@Getter
public class UploadClaimFailedException extends RuntimeException {
    private final int status;

    public UploadClaimFailedException(int status, String message) {
        super(message);
        this.status = status;
    }
}
