package com.tuapp.eventfoto.comment;

import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.OwnedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/events/{slug}/comments")
@RequiredArgsConstructor
public class AdminCommentController {

    private final CommentService commentService;

    /**
     * DELETE /api/v1/admin/events/{slug}/comments/{commentId}
     * Elimina un comentario de una foto (moderación). El comentario se busca dentro del
     * evento: uno de otro evento responde 404.
     */
    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> deleteComment(@OwnedEvent Event event, @PathVariable UUID commentId) {
        commentService.deleteComment(event.getId(), commentId);
        return ResponseEntity.noContent().build();
    }
}
