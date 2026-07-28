package com.tuapp.eventfoto.message;

import com.tuapp.eventfoto.message.dto.CreateMessageRequestDTO;
import com.tuapp.eventfoto.message.dto.MessageResponseDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/events/{slug}/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    /**
     * POST /api/v1/events/{slug}/messages
     * Deja un nuevo mensaje en el libro de visitas para Marcos y Priscila.
     */
    @PostMapping
    public ResponseEntity<MessageResponseDTO> addMessage(
            @PathVariable String slug,
            @Valid @RequestBody CreateMessageRequestDTO request,
            HttpServletRequest servletRequest) {

        String clientIp = getClientIp(servletRequest);
        MessageResponseDTO message = messageService.addMessage(slug, request, clientIp);
        return ResponseEntity.status(HttpStatus.CREATED).body(message);
    }

    /**
     * GET /api/v1/events/{slug}/messages?page=0&size=20
     * Lista los mensajes del libro de visitas (paginado).
     */
    @GetMapping
    public ResponseEntity<Page<MessageResponseDTO>> getMessages(
            @PathVariable String slug,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<MessageResponseDTO> messages = messageService.getMessages(slug, pageable);
        return ResponseEntity.ok(messages);
    }

    private String getClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null || xfHeader.isBlank()) {
            return request.getRemoteAddr();
        }
        return xfHeader.split(",")[0].trim();
    }
}
