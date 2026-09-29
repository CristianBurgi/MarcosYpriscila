package com.tuapp.eventfoto.superadmin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateFreeEventRequestDTO(
        @NotBlank(message = "El email no puede estar vacío")
        @Email(message = "Debe proporcionar un email válido")
        String email,

        @NotBlank(message = "El nombre del evento no puede estar vacío")
        @Size(max = 255, message = "El nombre del evento no puede superar los 255 caracteres")
        String eventName,

        @NotBlank(message = "El motivo de la cortesía no puede estar vacío")
        String reason
) {}
