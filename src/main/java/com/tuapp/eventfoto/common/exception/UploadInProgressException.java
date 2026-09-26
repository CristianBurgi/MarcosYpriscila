package com.tuapp.eventfoto.common.exception;

/**
 * Otra llamada a /confirm con la misma upload_key ya la reclamó y todavía está
 * procesando (conversión HEIC en curso). No es un error: se resuelve solo cuando el
 * ganador de la carrera termina. Mapea a 503 -- el mecanismo de reintento del frontend
 * (Fase 9.0 - Bloque C) lo trata como retryable y resuelve esto en su próximo intento.
 */
public class UploadInProgressException extends RuntimeException {
    public UploadInProgressException(String message) {
        super(message);
    }
}
