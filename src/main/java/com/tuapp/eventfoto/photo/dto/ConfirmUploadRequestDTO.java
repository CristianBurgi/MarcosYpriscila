package com.tuapp.eventfoto.photo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConfirmUploadRequestDTO(
    @NotBlank(message = "La clave del objeto (key) es obligatoria")
    String key,

    @Size(max = 150, message = "El nombre del invitado no puede superar los 150 caracteres")
    String uploaderName,

    @Size(max = 500, message = "La dedicatoria o pie de foto no puede superar los 500 caracteres")
    String caption,

    @NotBlank(message = "El token de invitado (guestToken) es obligatorio")
    String guestToken
) {}
