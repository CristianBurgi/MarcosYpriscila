package com.tuapp.eventfoto.common.exception;

import java.time.Instant;

public record ErrorResponseDTO(
    Instant timestamp,
    int status,
    String error,
    String message,
    String path
) {
    public static ErrorResponseDTO of(int status, String error, String message, String path) {
        return new ErrorResponseDTO(Instant.now(), status, error, message, path);
    }
}
