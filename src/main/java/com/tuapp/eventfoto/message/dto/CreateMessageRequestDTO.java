package com.tuapp.eventfoto.message.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateMessageRequestDTO(
    @NotBlank(message = "El nombre del invitado es obligatorio")
    @Size(max = 150, message = "El nombre del invitado no puede superar los 150 caracteres")
    String authorName,

    @NotBlank(message = "El mensaje para los novios no puede estar vacío")
    @Size(max = 1000, message = "El mensaje del libro de visitas no puede superar los 1000 caracteres")
    String text
) {}
