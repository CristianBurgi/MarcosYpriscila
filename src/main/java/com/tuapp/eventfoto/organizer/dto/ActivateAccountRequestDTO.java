package com.tuapp.eventfoto.organizer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ActivateAccountRequestDTO(
        @NotBlank(message = "El token no puede estar vacío")
        String token,

        @NotBlank(message = "La contraseña no puede estar vacía")
        @Size(min = 8, message = "La contraseña tiene que tener al menos 8 caracteres")
        String password
) {}
