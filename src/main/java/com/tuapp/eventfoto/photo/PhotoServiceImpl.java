package com.tuapp.eventfoto.photo;

import com.tuapp.eventfoto.comment.Comment;
import com.tuapp.eventfoto.comment.CommentRepository;
import com.tuapp.eventfoto.common.config.RateLimiterService;
import com.tuapp.eventfoto.common.exception.ResourceNotFoundException;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventService;
import com.tuapp.eventfoto.photo.dto.ConfirmUploadRequestDTO;
import com.tuapp.eventfoto.photo.dto.PhotoResponseDTO;
import com.tuapp.eventfoto.photo.dto.UploadUrlRequestDTO;
import com.tuapp.eventfoto.photo.dto.UploadUrlResponseDTO;
import com.tuapp.eventfoto.realtime.SseBroadcaster;
import com.tuapp.eventfoto.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tuapp.eventfoto.common.exception.InvalidFileFormatException;
import com.tuapp.eventfoto.common.exception.StorageException;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PhotoServiceImpl implements PhotoService {

    private final PhotoRepository photoRepository;
    private final CommentRepository commentRepository;
    private final EventService eventService;
    private final StorageService storageService;
    private final SseBroadcaster sseBroadcaster;
    private final RateLimiterService rateLimiterService;

    @Override
    public UploadUrlResponseDTO generateUploadUrl(String slug, UploadUrlRequestDTO request, String clientIp) {
        // 1. Aplicar rate limiting
        rateLimiterService.checkUploadUrlRateLimit(clientIp);

        // 2. Validar que el evento exista por slug
        eventService.getEventEntityBySlug(slug);

        String contentType = request.contentType();
        String filename = request.filename();

        String extension = getFileExtension(filename, contentType);
        String key = String.format("photos/%s/%s%s", slug, UUID.randomUUID(), extension);

        String presignedUrl = storageService.generateUploadUrl(key, contentType);
        String publicUrl = storageService.generatePublicUrl(key);

        return new UploadUrlResponseDTO(presignedUrl, key, publicUrl);
    }

    @Override
    @Transactional
    public PhotoResponseDTO confirmUpload(String slug, ConfirmUploadRequestDTO request) {
        Event event = eventService.getEventEntityBySlug(slug);

        String uploader = request.uploaderName() != null && !request.uploaderName().isBlank() ? request.uploaderName().trim() : "Invitado";

        Photo photo = Photo.builder()
                .event(event)
                .storageKey(request.key())
                .uploaderName(uploader)
                .isApproved(false) // Requiere aprobación previa por moderación de admin
                .build();

        Photo savedPhoto = photoRepository.save(photo);

        // Si la foto vino con una dedicatoria/caption opcional, guardarla como el primer comentario
        if (request.caption() != null && !request.caption().isBlank()) {
            Comment captionComment = Comment.builder()
                    .photo(savedPhoto)
                    .authorName(uploader)
                    .text(request.caption().trim())
                    .isApproved(true)
                    .build();
            commentRepository.save(captionComment);
            savedPhoto.getComments().add(captionComment);
        }

        log.info("Foto confirmada y guardada con ID {} para el evento '{}' (pendiente de aprobación)", savedPhoto.getId(), slug);

        String publicUrl = storageService.generatePublicUrl(savedPhoto.getStorageKey());
        PhotoResponseDTO response = PhotoResponseDTO.fromEntity(savedPhoto, publicUrl);
        sseBroadcaster.broadcastPhotoPending(event.getId(), response);
        return response;
    }

    @Override
    @Transactional
    public PhotoResponseDTO uploadDirect(String slug, org.springframework.web.multipart.MultipartFile file, String uploaderName, String caption) {
        if (file == null || file.isEmpty()) {
            throw new InvalidFileFormatException("El archivo enviado está vacío.");
        }

        Event event = eventService.getEventEntityBySlug(slug);

        String contentType = file.getContentType();
        if (contentType == null || contentType.isBlank()) {
            contentType = "image/jpeg";
        }
        if (contentType.equalsIgnoreCase("image/jpg")) {
            contentType = "image/jpeg";
        }

        String originalFilename = file.getOriginalFilename();
        String extension = ".jpg";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase();
        }

        String key = String.format("photos/%s/%s%s", slug, UUID.randomUUID(), extension);

        try {
            byte[] bytes = file.getBytes();
            storageService.uploadBytes(key, bytes, contentType);

            Photo photo = Photo.builder()
                    .event(event)
                    .storageKey(key)
                    .uploaderName(uploaderName)
                    .isApproved(false)
                    .createdAt(Instant.now())
                    .build();

            Photo saved = photoRepository.save(photo);

            if (caption != null && !caption.isBlank()) {
                Comment captionComment = Comment.builder()
                        .photo(saved)
                        .authorName(uploaderName)
                        .text(caption.trim())
                        .isApproved(true)
                        .build();
                commentRepository.save(captionComment);
                saved.getComments().add(captionComment);
            }

            log.info("Foto subida de forma directa y guardada en BD con ID {} para el evento '{}'", saved.getId(), slug);
            String publicUrl = storageService.generatePublicUrl(saved.getStorageKey());
            PhotoResponseDTO response = PhotoResponseDTO.fromEntity(saved, publicUrl, Collections.emptyList());
            sseBroadcaster.broadcastPhotoPending(event.getId(), response);
            return response;

        } catch (IOException e) {
            log.error("Error al procesar los bytes de la imagen subida directamente: {}", e.getMessage(), e);
            throw new StorageException("Error al procesar la imagen en el servidor", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PhotoResponseDTO> getApprovedPhotos(String slug, Pageable pageable) {
        Event event = eventService.getEventEntityBySlug(slug);
        return photoRepository.findByEventIdAndIsApprovedTrueOrderByCreatedAtDesc(event.getId(), pageable)
                .map(photo -> {
                    List<Comment> comments = commentRepository.findByPhotoIdAndIsApprovedTrueOrderByCreatedAtAsc(photo.getId());
                    return PhotoResponseDTO.fromEntity(photo, storageService.generatePublicUrl(photo.getStorageKey()), comments);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PhotoResponseDTO> getPendingPhotos(String slug, Pageable pageable) {
        Event event = eventService.getEventEntityBySlug(slug);
        return photoRepository.findByEventIdAndIsApprovedFalseOrderByCreatedAtAsc(event.getId(), pageable)
                .map(photo -> {
                    List<Comment> comments = commentRepository.findByPhotoIdAndIsApprovedTrueOrderByCreatedAtAsc(photo.getId());
                    return PhotoResponseDTO.fromEntity(photo, storageService.generatePublicUrl(photo.getStorageKey()), comments);
                });
    }

    @Override
    @Transactional
    public PhotoResponseDTO approvePhoto(UUID photoId) {
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new ResourceNotFoundException("No se encontró la fotografía con ID: " + photoId));

        photo.setApproved(true);
        Photo approvedPhoto = photoRepository.save(photo);
        log.info("Fotografía con ID {} aprobada por administración", photoId);

        List<Comment> comments = commentRepository.findByPhotoIdAndIsApprovedTrueOrderByCreatedAtAsc(photoId);

        String publicUrl = storageService.generatePublicUrl(approvedPhoto.getStorageKey());
        PhotoResponseDTO response = PhotoResponseDTO.fromEntity(approvedPhoto, publicUrl, comments);
        sseBroadcaster.broadcastPhotoApproved(photo.getEvent().getId(), response);

        return response;
    }

    @Override
    @Transactional
    public void deletePhoto(UUID photoId) {
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new ResourceNotFoundException("No se encontró la fotografía con ID: " + photoId));

        String storageKey = photo.getStorageKey();
        if (storageKey != null && !storageKey.isBlank()) {
            try {
                storageService.deleteFile(storageKey);
                log.info("Archivo en almacenamiento con clave '{}' eliminado para la foto ID {}", storageKey, photoId);
            } catch (Exception e) {
                log.warn("Fallo al eliminar archivo en almacenamiento para clave '{}' (foto ID {}): {}. Se procederá a eliminar la foto de la BD de todas formas.",
                        storageKey, photoId, e.getMessage());
            }
        }

        photoRepository.delete(photo);
        log.info("Fotografía con ID {} eliminada exitosamente de la base de datos por administración", photoId);
    }

    @Override
    @Transactional
    public void rejectPhoto(UUID photoId) {
        deletePhoto(photoId);
    }

    @Override
    @Transactional
    public List<PhotoResponseDTO> approveAllPendingPhotos(String slug) {
        Event event = eventService.getEventEntityBySlug(slug);
        List<Photo> pendingPhotos = photoRepository.findByEventIdAndIsApprovedFalse(event.getId());

        List<PhotoResponseDTO> approvedDTOs = new ArrayList<>();
        for (Photo photo : pendingPhotos) {
            photo.setApproved(true);
            Photo saved = photoRepository.save(photo);
            List<Comment> comments = commentRepository.findByPhotoIdAndIsApprovedTrueOrderByCreatedAtAsc(saved.getId());
            String publicUrl = storageService.generatePublicUrl(saved.getStorageKey());
            PhotoResponseDTO response = PhotoResponseDTO.fromEntity(saved, publicUrl, comments);
            
            // Emitir evento SSE por cada foto aprobada en masa
            sseBroadcaster.broadcastPhotoApproved(event.getId(), response);
            approvedDTOs.add(response);
        }

        log.info("Se aprobaron en masa {} fotografías pendientes para el evento '{}'", approvedDTOs.size(), slug);
        return approvedDTOs;
    }

    @Override
    @Transactional(readOnly = true)
    public long countTotalPhotos(String slug) {
        Event event = eventService.getEventEntityBySlug(slug);
        return photoRepository.countByEventId(event.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public long countPendingPhotos(String slug) {
        Event event = eventService.getEventEntityBySlug(slug);
        return photoRepository.countByEventIdAndIsApprovedFalse(event.getId());
    }

    private String getFileExtension(String filename, String contentType) {
        if (filename != null && filename.contains(".")) {
            return filename.substring(filename.lastIndexOf("."));
        }
        if (contentType == null) {
            return ".jpg";
        }
        return switch (contentType.toLowerCase()) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/heic" -> ".heic";
            default -> ".jpg";
        };
    }
}
