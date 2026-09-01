package com.tuapp.eventfoto.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCommentRequestDTO(
    @NotBlank(message = "El nombre del autor es obligatorio")
    @Size(max = 150, message = "El nombre del autor no puede superar los 150 caracteres")
    String authorName,

    @NotBlank(message = "El comentario no puede estar vacío")
    @Size(max = 500, message = "El texto del comentario no puede superar los 500 caracteres")
    String text,

    // Identidad del invitado generada en el navegador (la misma que usa para subir fotos).
    // Opcional a propósito: si un cliente con el HTML cacheado todavía no lo manda, el rate
    // limiting cae a la capa por IP en lugar de rechazar el comentario con un 400.
    @Size(max = 64, message = "El token de invitado no puede superar los 64 caracteres")
    String guestToken
) {
    // Compatibilidad: clientes/tests que aún no envían guestToken.
    public CreateCommentRequestDTO(String authorName, String text) {
        this(authorName, text, null);
    }
}
