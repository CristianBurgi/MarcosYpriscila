package com.tuapp.eventfoto.common.exception;

/**
 * Se lanza cuando el contenido binario real de un archivo subido (magic bytes)
 * no corresponde a ningún formato de imagen permitido, sin importar qué
 * Content-Type haya declarado el cliente.
 *
 * Distinta de {@link InvalidFileFormatException} (que rechaza por el
 * Content-Type declarado, sin inspeccionar bytes): esta representa una
 * verificación de contenido real, por eso se mapea a 422 Unprocessable Entity.
 */
public class InvalidFileContentException extends RuntimeException {

    public InvalidFileContentException(String message) {
        super(message);
    }
}
