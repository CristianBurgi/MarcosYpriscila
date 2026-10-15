package com.tuapp.eventfoto.organizer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetRequestDTO(
        @NotBlank(message = "Escribí tu email")
        @Email(message = "Ese email no es válido")
        @Size(max = 254, message = "Ese email no es válido")
        String email
) {}
