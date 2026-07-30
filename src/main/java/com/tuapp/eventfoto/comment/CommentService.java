package com.tuapp.eventfoto.comment;

import com.tuapp.eventfoto.comment.dto.CommentResponseDTO;
import com.tuapp.eventfoto.comment.dto.CreateCommentRequestDTO;

import java.util.List;
import java.util.UUID;

public interface CommentService {

    CommentResponseDTO addComment(UUID photoId, CreateCommentRequestDTO request, String clientIp);

    List<CommentResponseDTO> getPhotoComments(UUID photoId);

    List<CommentResponseDTO> getEventComments(String slug);

    void deleteComment(UUID commentId);
}
