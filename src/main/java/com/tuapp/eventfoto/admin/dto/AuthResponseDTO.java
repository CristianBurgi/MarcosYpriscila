package com.tuapp.eventfoto.admin.dto;

public record AuthResponseDTO(
        String token,
        String email,
        String tokenType,
        long expiresInMs
) {
    public AuthResponseDTO(String token, String email, long expiresInMs) {
        this(token, email, "Bearer", expiresInMs);
    }
}
