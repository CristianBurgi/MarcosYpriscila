package com.tuapp.eventfoto.message;

import com.tuapp.eventfoto.message.dto.CreateMessageRequestDTO;
import com.tuapp.eventfoto.message.dto.MessageResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface MessageService {

    MessageResponseDTO addMessage(String slug, CreateMessageRequestDTO request, String clientIp);

    Page<MessageResponseDTO> getMessages(String slug, Pageable pageable);

    long countTotalMessages(String slug);

    void deleteMessage(java.util.UUID messageId);
}
