package com.tuapp.eventfoto.common.exception;

/** El archivo supera app.upload.max-file-bytes. Se responde 413 con el mensaje para el invitado. */
public class FileTooLargeException extends RuntimeException {

    public FileTooLargeException(String message) {
        super(message);
    }
}
