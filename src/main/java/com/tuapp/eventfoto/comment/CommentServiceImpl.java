package com.tuapp.eventfoto.comment;

import com.tuapp.eventfoto.comment.dto.CommentResponseDTO;
import com.tuapp.eventfoto.comment.dto.CreateCommentRequestDTO;
import com.tuapp.eventfoto.common.config.RateLimiterService;
import com.tuapp.eventfoto.common.exception.ContentModerationException;
import com.tuapp.eventfoto.common.exception.ResourceNotFoundException;
import com.tuapp.eventfoto.common.moderation.ContentModerationService;
import com.tuapp.eventfoto.photo.Photo;
import com.tuapp.eventfoto.photo.PhotoRepository;
import com.tuapp.eventfoto.realtime.SseBroadcaster;
import com.tuapp.eventfoto.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {

    private final CommentRepository commentRepository;
    private final PhotoRepository photoRepository;
    private final RateLimiterService rateLimiterService;
    private final ContentModerationService contentModerationService;
    private final SseBroadcaster sseBroadcaster;
    private final StorageService storageService;

    @Override
    @Transactional
    public CommentResponseDTO addComment(UUID photoId, CreateCommentRequestDTO request, String clientIp) {
        // 1. Aplicar rate limiting (máx 3 envíos por minuto)
        rateLimiterService.checkCommentMessageRateLimit(clientIp);

        // 2. Moderación automática de contenido
        if (!contentModerationService.isAllowed(request.text())) {
            log.warn("Comentario de foto bloqueado por el filtro de moderación de contenido para la foto {}", photoId);
            throw new ContentModerationException("Tu comentario no pudo publicarse, revisá el contenido e intentá de nuevo.");
        }

        // 3. Validar que la foto exista
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new ResourceNotFoundException("No se encontró la fotografía con ID: " + photoId));

        // 4. Crear el comentario
        Comment comment = Comment.builder()
                .photo(photo)
                .authorName(request.authorName().trim())
                .text(request.text().trim())
                .isApproved(true)
                .build();

        Comment savedComment = commentRepository.save(comment);
        log.info("Nuevo comentario registrado en foto {} por '{}'", photoId, request.authorName());

        return CommentResponseDTO.fromEntity(savedComment);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CommentResponseDTO> getPhotoComments(UUID photoId) {
        // Validar que la foto exista
        if (!photoRepository.existsById(photoId)) {
            throw new ResourceNotFoundException("No se encontró la fotografía con ID: " + photoId);
        }

        return commentRepository.findByPhotoIdAndIsApprovedTrueOrderByCreatedAtDesc(photoId)
                .stream()
                .map(CommentResponseDTO::fromEntity)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CommentResponseDTO> getEventComments(String slug) {
        return commentRepository.findByPhotoEventSlugAndIsApprovedTrueOrderByCreatedAtDesc(slug)
                .stream()
                .map(comment -> {
                    String photoUrl = null;
                    if (comment.getPhoto() != null && comment.getPhoto().getStorageKey() != null) {
                        photoUrl = storageService.generatePublicUrl(comment.getPhoto().getStorageKey());
                    }
                    return CommentResponseDTO.fromEntity(comment, photoUrl);
                })
                .toList();
    }

    @Override
    @Transactional
    public void deleteComment(UUID commentId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("No se encontró el comentario con ID: " + commentId));
        
        UUID photoId = comment.getPhoto() != null ? comment.getPhoto().getId() : null;
        UUID eventId = (comment.getPhoto() != null && comment.getPhoto().getEvent() != null) ? comment.getPhoto().getEvent().getId() : null;

        commentRepository.delete(comment);
        log.info("Comentario con ID {} eliminado exitosamente por administración", commentId);

        if (eventId != null && photoId != null) {
            sseBroadcaster.broadcastCommentDeleted(eventId, commentId, photoId);
        }
    }
}
