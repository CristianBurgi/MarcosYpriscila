package com.tuapp.eventfoto.common.exception;

/**
 * El token de activación/reseteo no existe, ya se usó o venció. Un solo mensaje
 * genérico para los tres casos: no hay ninguna necesidad de decirle al que lo mira
 * cuál de los tres pasó.
 */
public class InvalidActivationTokenException extends RuntimeException {
    public InvalidActivationTokenException(String message) {
        super(message);
    }
}
