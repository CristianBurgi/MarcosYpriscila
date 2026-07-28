package com.tuapp.eventfoto.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequestDTO(
        @NotBlank(message = "El email no puede estar vacío")
        @Email(message = "Debe proporcionar un email válido")
        String email,

        @NotBlank(message = "La contraseña no puede estar vacía")
        String password
) {}
