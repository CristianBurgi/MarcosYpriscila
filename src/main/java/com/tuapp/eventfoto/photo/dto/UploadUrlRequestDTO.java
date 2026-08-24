package com.tuapp.eventfoto.photo.dto;

import jakarta.validation.constraints.NotBlank;

public record UploadUrlRequestDTO(
    @NotBlank(message = "El tipo de contenido (contentType) es obligatorio (ej: image/jpeg)")
    String contentType,
    String filename,

    @NotBlank(message = "El token de invitado (guestToken) es obligatorio")
    String guestToken
) {}
