package com.tuapp.eventfoto.comment;

import com.tuapp.eventfoto.comment.dto.CommentResponseDTO;
import com.tuapp.eventfoto.comment.dto.CreateCommentRequestDTO;
import com.tuapp.eventfoto.common.config.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events/{slug}/photos/{photoId}/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;
    private final ClientIpResolver clientIpResolver;

    /**
     * POST /api/v1/events/{slug}/photos/{photoId}/comments
     * Agrega un nuevo comentario a una fotografía.
     */
    @PostMapping
    public ResponseEntity<CommentResponseDTO> addComment(
            @PathVariable String slug,
            @PathVariable UUID photoId,
            @Valid @RequestBody CreateCommentRequestDTO request,
            HttpServletRequest servletRequest) {

        String clientIp = clientIpResolver.resolve(servletRequest);
        CommentResponseDTO comment = commentService.addComment(slug, photoId, request, clientIp);
        return ResponseEntity.status(HttpStatus.CREATED).body(comment);
    }

    /**
     * GET /api/v1/events/{slug}/photos/{photoId}/comments
     * Lista los comentarios de una fotografía.
     */
    @GetMapping
    public ResponseEntity<List<CommentResponseDTO>> getPhotoComments(@PathVariable String slug, @PathVariable UUID photoId) {
        List<CommentResponseDTO> comments = commentService.getPhotoComments(slug, photoId);
        return ResponseEntity.ok(comments);
    }
}
