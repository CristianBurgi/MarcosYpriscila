package com.tuapp.eventfoto.message;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/messages")
@RequiredArgsConstructor
public class AdminMessageController {

    private final MessageService messageService;

    /**
     * DELETE /api/v1/admin/messages/{messageId}
     * Elimina un mensaje del libro de visitas (moderación administrativa).
     */
    @DeleteMapping("/{messageId}")
    public ResponseEntity<Void> deleteMessage(@PathVariable UUID messageId) {
        messageService.deleteMessage(messageId);
        return ResponseEntity.noContent().build();
    }
}
