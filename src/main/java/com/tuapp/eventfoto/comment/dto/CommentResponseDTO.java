package com.tuapp.eventfoto.comment.dto;

import com.tuapp.eventfoto.comment.Comment;

import java.time.Instant;
import java.util.UUID;

public record CommentResponseDTO(
    UUID id,
    UUID photoId,
    String authorName,
    String text,
    boolean isApproved,
    Instant createdAt
) {
    public static CommentResponseDTO fromEntity(Comment comment) {
        return new CommentResponseDTO(
            comment.getId(),
            comment.getPhoto() != null ? comment.getPhoto().getId() : null,
            comment.getAuthorName(),
            comment.getText(),
            comment.isApproved(),
            comment.getCreatedAt()
        );
    }
}
