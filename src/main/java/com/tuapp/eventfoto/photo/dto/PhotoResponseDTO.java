package com.tuapp.eventfoto.photo.dto;

import com.tuapp.eventfoto.comment.Comment;
import com.tuapp.eventfoto.comment.dto.CommentResponseDTO;
import com.tuapp.eventfoto.photo.Photo;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public record PhotoResponseDTO(
    UUID id,
    UUID eventId,
    String storageKey,
    String url,
    String uploaderName,
    boolean isApproved,
    Instant createdAt,
    int commentCount,
    List<CommentResponseDTO> comments
) {
    public static PhotoResponseDTO fromEntity(Photo photo) {
        return fromEntity(photo, photo.getStorageKey(), photo.getComments());
    }

    public static PhotoResponseDTO fromEntity(Photo photo, String publicUrl) {
        return fromEntity(photo, publicUrl, photo.getComments());
    }

    public static PhotoResponseDTO fromEntity(Photo photo, String publicUrl, List<Comment> commentsList) {
        List<CommentResponseDTO> commentDtos = commentsList != null ?
                commentsList.stream()
                        .filter(Comment::isApproved)
                        .map(CommentResponseDTO::fromEntity)
                        .toList()
                : Collections.emptyList();

        return new PhotoResponseDTO(
            photo.getId(),
            photo.getEvent() != null ? photo.getEvent().getId() : null,
            photo.getStorageKey(),
            publicUrl != null ? publicUrl : photo.getStorageKey(),
            photo.getUploaderName(),
            photo.isApproved(),
            photo.getCreatedAt(),
            commentDtos.size(),
            commentDtos
        );
    }
}
