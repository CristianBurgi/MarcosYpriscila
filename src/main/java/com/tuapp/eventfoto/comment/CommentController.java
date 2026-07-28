package com.tuapp.eventfoto.comment;

import com.tuapp.eventfoto.comment.dto.CommentResponseDTO;
import com.tuapp.eventfoto.comment.dto.CreateCommentRequestDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/photos/{photoId}/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    /**
     * POST /api/v1/photos/{photoId}/comments
     * Agrega un nuevo comentario a una fotografía.
     */
    @PostMapping
    public ResponseEntity<CommentResponseDTO> addComment(
            @PathVariable UUID photoId,
            @Valid @RequestBody CreateCommentRequestDTO request,
            HttpServletRequest servletRequest) {

        String clientIp = getClientIp(servletRequest);
        CommentResponseDTO comment = commentService.addComment(photoId, request, clientIp);
        return ResponseEntity.status(HttpStatus.CREATED).body(comment);
    }

    /**
     * GET /api/v1/photos/{photoId}/comments
     * Lista los comentarios de una fotografía.
     */
    @GetMapping
    public ResponseEntity<List<CommentResponseDTO>> getPhotoComments(@PathVariable UUID photoId) {
        List<CommentResponseDTO> comments = commentService.getPhotoComments(photoId);
        return ResponseEntity.ok(comments);
    }

    private String getClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null || xfHeader.isBlank()) {
            return request.getRemoteAddr();
        }
        return xfHeader.split(",")[0].trim();
    }
}
