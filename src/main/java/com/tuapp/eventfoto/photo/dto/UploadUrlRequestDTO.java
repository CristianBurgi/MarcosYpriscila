package com.tuapp.eventfoto.photo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UploadUrlRequestDTO(
    @NotBlank(message = "El tipo de contenido (contentType) es obligatorio (ej: image/jpeg)")
    String contentType,
    String filename,

    @NotBlank(message = "El token de invitado (guestToken) es obligatorio")
    @Size(max = 64, message = "El token de invitado no puede superar los 64 caracteres")
    String guestToken
) {}
