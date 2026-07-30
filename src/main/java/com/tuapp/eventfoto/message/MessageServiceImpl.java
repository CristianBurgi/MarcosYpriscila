package com.tuapp.eventfoto.message;

import com.tuapp.eventfoto.common.config.RateLimiterService;
import com.tuapp.eventfoto.common.exception.ContentModerationException;
import com.tuapp.eventfoto.common.exception.ResourceNotFoundException;
import com.tuapp.eventfoto.common.moderation.ContentModerationService;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventService;
import com.tuapp.eventfoto.message.dto.CreateMessageRequestDTO;
import com.tuapp.eventfoto.message.dto.MessageResponseDTO;
import com.tuapp.eventfoto.realtime.SseBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageServiceImpl implements MessageService {

    private final MessageRepository messageRepository;
    private final EventService eventService;
    private final RateLimiterService rateLimiterService;
    private final ContentModerationService contentModerationService;
    private final SseBroadcaster sseBroadcaster;

    @Override
    @Transactional
    public MessageResponseDTO addMessage(String slug, CreateMessageRequestDTO request, String clientIp) {
        // 1. Aplicar rate limiting (máx 3 envíos por minuto)
        rateLimiterService.checkCommentMessageRateLimit(clientIp);

        // 2. Moderación automática de contenido
        if (!contentModerationService.isAllowed(request.text())) {
            log.warn("Mensaje bloqueado por el filtro de moderación de contenido para el evento '{}' de '{}'", slug, request.authorName());
            throw new ContentModerationException("Tu mensaje no pudo publicarse, revisá el contenido e intentá de nuevo.");
        }

        // 3. Validar que el evento exista por slug
        Event event = eventService.getEventEntityBySlug(slug);

        // 4. Crear el mensaje del libro de visitas
        Message message = Message.builder()
                .event(event)
                .authorName(request.authorName().trim())
                .text(request.text().trim())
                .isApproved(true)
                .build();

        Message savedMessage = messageRepository.save(message);
        log.info("Nuevo mensaje guardado en libro de visitas para el evento '{}' por '{}'", slug, request.authorName());

        MessageResponseDTO response = MessageResponseDTO.fromEntity(savedMessage);
        sseBroadcaster.broadcastMessageCreated(event.getId(), response);

        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MessageResponseDTO> getMessages(String slug, Pageable pageable) {
        Event event = eventService.getEventEntityBySlug(slug);
        return messageRepository.findByEventIdAndIsApprovedTrueOrderByCreatedAtDesc(event.getId(), pageable)
                .map(MessageResponseDTO::fromEntity);
    }

    @Override
    @Transactional(readOnly = true)
    public long countTotalMessages(String slug) {
        Event event = eventService.getEventEntityBySlug(slug);
        return messageRepository.countByEventId(event.getId());
    }

    @Override
    @Transactional
    public void deleteMessage(UUID messageId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("No se encontró el mensaje con ID: " + messageId));

        messageRepository.delete(message);
        log.info("Mensaje con ID {} eliminado exitosamente por administración", messageId);

        sseBroadcaster.broadcastMessageDeleted(message.getEvent().getId(), messageId);
    }
}
