package com.tuapp.eventfoto.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCommentRequestDTO(
    @NotBlank(message = "El nombre del autor es obligatorio")
    @Size(max = 150, message = "El nombre del autor no puede superar los 150 caracteres")
    String authorName,

    @NotBlank(message = "El comentario no puede estar vacío")
    @Size(max = 500, message = "El texto del comentario no puede superar los 500 caracteres")
    String text
) {}
