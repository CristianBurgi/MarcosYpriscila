package com.tuapp.eventfoto.message.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateMessageRequestDTO(
    @NotBlank(message = "El nombre del invitado es obligatorio")
    @Size(max = 150, message = "El nombre del invitado no puede superar los 150 caracteres")
    String authorName,

    @NotBlank(message = "El mensaje para los novios no puede estar vacío")
    @Size(max = 1000, message = "El mensaje del libro de visitas no puede superar los 1000 caracteres")
    String text,

    // Identidad del invitado generada en el navegador (la misma que usa para subir fotos).
    // Opcional a propósito: si un cliente con el HTML cacheado todavía no lo manda, el rate
    // limiting cae a la capa por IP en lugar de rechazar el mensaje con un 400.
    @Size(max = 64, message = "El token de invitado no puede superar los 64 caracteres")
    String guestToken
) {
    // Compatibilidad: clientes/tests que aún no envían guestToken.
    public CreateMessageRequestDTO(String authorName, String text) {
        this(authorName, text, null);
    }
}
