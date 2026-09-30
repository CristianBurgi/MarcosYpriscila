package com.tuapp.eventfoto.message;

import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.OwnedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/events/{slug}/messages")
@RequiredArgsConstructor
public class AdminMessageController {

    private final MessageService messageService;

    /**
     * DELETE /api/v1/admin/events/{slug}/messages/{messageId}
     * Elimina un mensaje del libro de visitas (moderación). El mensaje se busca dentro
     * del evento: uno de otro evento responde 404.
     */
    @DeleteMapping("/{messageId}")
    public ResponseEntity<Void> deleteMessage(@OwnedEvent Event event, @PathVariable UUID messageId) {
        messageService.deleteMessage(event.getId(), messageId);
        return ResponseEntity.noContent().build();
    }
}
