package com.tuapp.eventfoto.message;

import com.tuapp.eventfoto.message.dto.CreateMessageRequestDTO;
import com.tuapp.eventfoto.message.dto.MessageResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface MessageService {

    MessageResponseDTO addMessage(String slug, CreateMessageRequestDTO request, String clientIp);

    Page<MessageResponseDTO> getMessages(String slug, Pageable pageable);

    long countTotalMessages(String slug);

    /** Borrado desde el panel del organizador. */
    void deleteMessage(java.util.UUID eventId, java.util.UUID messageId);

    /** Mismo borrado (base -> SSE), indicando quién lo hace solo para el log. */
    void deleteMessage(java.util.UUID eventId, java.util.UUID messageId, com.tuapp.eventfoto.common.config.DeletionActor actor);
}
