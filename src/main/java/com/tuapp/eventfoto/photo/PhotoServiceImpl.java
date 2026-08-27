package com.tuapp.eventfoto.photo;

import com.tuapp.eventfoto.comment.Comment;
import com.tuapp.eventfoto.comment.CommentRepository;
import com.tuapp.eventfoto.common.config.RateLimiterService;
import com.tuapp.eventfoto.common.exception.EventClosedException;
import com.tuapp.eventfoto.common.exception.GuestQuotaExceededException;
import com.tuapp.eventfoto.common.exception.InvalidFileContentException;
import com.tuapp.eventfoto.common.exception.InvalidFileFormatException;
import com.tuapp.eventfoto.common.exception.ResourceNotFoundException;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventService;
import com.tuapp.eventfoto.photo.dto.ConfirmUploadRequestDTO;
import com.tuapp.eventfoto.photo.dto.PhotoResponseDTO;
import com.tuapp.eventfoto.photo.dto.UploadUrlRequestDTO;
import com.tuapp.eventfoto.photo.dto.UploadUrlResponseDTO;
import com.tuapp.eventfoto.realtime.SseBroadcaster;
import com.tuapp.eventfoto.storage.FileSignatureValidator;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
    private final GuestQuotaService guestQuotaService;

    @Override
    public UploadUrlResponseDTO generateUploadUrl(String slug, UploadUrlRequestDTO request, String clientIp, String guestToken) {
        // 1. Aplicar rate limiting en dos capas: por guestToken (primario) + por IP (secundario)
        rateLimiterService.checkUploadUrlRateLimit(clientIp, guestToken);

        // 2. Validar que el evento exista por slug y esté activo
        Event event = eventService.getEventEntityBySlug(slug);
        if (!event.isActive()) {
            throw new EventClosedException("La recepción de fotografías para este evento se encuentra cerrada por los novios.");
        }

        // 3. Chequeo previo (no atómico) del cupo del invitado: evita gastar una presigned
        // URL si ya sabemos que no tiene fotos disponibles. La verdad final y atómica se
        // aplica en confirmUpload() vía GuestQuotaService.incrementUsageOrThrow().
        guestQuotaService.assertQuotaAvailable(event.getId(), request.guestToken());

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
        if (!event.isActive()) {
            throw new EventClosedException("La recepción de fotografías para este evento se encuentra cerrada por los novios.");
        }

        // Verificación de contenido real (magic bytes) del objeto ya subido a storage,
        // independiente del Content-Type que haya declarado el cliente. Si no es un
        // objeto contra el que se pueda validar (ver validateUploadedFileSignature),
        // se elimina de storage y se rechaza antes de crear cualquier registro en BD.
        validateUploadedFileSignature(request.key());

        // Incremento atómico del cupo del invitado, solo después de confirmar que el
        // archivo es una imagen real. Si el cupo ya está agotado (incluso por una
        // condición de carrera entre dos pestañas), se rechaza acá, antes de crear el
        // registro Photo -- el cupo consumido no se puede "devolver" después. El objeto
        // ya está en storage (lo subió el cliente vía la presigned URL antes de llamar a
        // /confirm), así que si el cupo lo rechaza acá, se elimina para no dejar basura.
        try {
            guestQuotaService.incrementUsageOrThrow(event, request.guestToken());
        } catch (GuestQuotaExceededException e) {
            log.warn("Cupo agotado al confirmar; eliminando objeto huérfano '{}' de storage", request.key());
            try {
                storageService.deleteFile(request.key());
            } catch (Exception cleanupEx) {
                log.error("No se pudo eliminar el objeto huérfano '{}' tras rechazo por cupo: {}", request.key(), cleanupEx.getMessage());
            }
            throw e;
        }

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
    public PhotoResponseDTO uploadDirect(String slug, org.springframework.web.multipart.MultipartFile file, String uploaderName, String caption, String guestToken) {
        if (file == null || file.isEmpty()) {
            throw new InvalidFileFormatException("El archivo enviado está vacío.");
        }

        Event event = eventService.getEventEntityBySlug(slug);
        if (!event.isActive()) {
            throw new EventClosedException("La recepción de fotografías para este evento se encuentra cerrada por los novios.");
        }

        // Chequeo previo (no atómico) del cupo del invitado: evita gastar tiempo/CPU
        // subiendo bytes a storage si ya sabemos que no tiene fotos disponibles.
        guestQuotaService.assertQuotaAvailable(event.getId(), guestToken);

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

            // Verificación de contenido real (magic bytes) antes de subir a storage:
            // el Content-Type multipart declarado por el cliente puede mentir.
            if (!FileSignatureValidator.isValidImageSignature(headerOf(bytes))) {
                log.warn("Subida directa rechazada: la firma binaria del archivo no corresponde a una imagen válida (Content-Type declarado: '{}')", contentType);
                throw new InvalidFileContentException(
                        "El archivo subido no corresponde a una imagen válida (JPEG, PNG, WEBP o HEIC). La subida fue rechazada."
                );
            }

            storageService.uploadBytes(key, bytes, contentType);

            // Incremento atómico del cupo del invitado, solo después de que la subida a
            // storage tuvo éxito -- mismo criterio que en confirmUpload: se cuenta
            // únicamente lo que realmente terminó guardado. Si el cupo lo rechaza justo
            // acá (condición de carrera), se elimina el objeto recién subido para no
            // dejar basura en storage sin un registro Photo que lo referencie.
            try {
                guestQuotaService.incrementUsageOrThrow(event, guestToken);
            } catch (GuestQuotaExceededException e) {
                log.warn("Cupo agotado tras subir a storage en upload-direct; eliminando objeto huérfano '{}'", key);
                try {
                    storageService.deleteFile(key);
                } catch (Exception cleanupEx) {
                    log.error("No se pudo eliminar el objeto huérfano '{}' tras rechazo por cupo: {}", key, cleanupEx.getMessage());
                }
                throw e;
            }

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
                    List<Comment> comments = commentRepository.findByPhotoIdAndIsApprovedTrueOrderByCreatedAtDesc(photo.getId());
                    return PhotoResponseDTO.fromEntity(photo, storageService.generatePublicUrl(photo.getStorageKey()), comments);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PhotoResponseDTO> getPendingPhotos(String slug, Pageable pageable) {
        Event event = eventService.getEventEntityBySlug(slug);
        return photoRepository.findByEventIdAndIsApprovedFalseOrderByCreatedAtAsc(event.getId(), pageable)
                .map(photo -> {
                    List<Comment> comments = commentRepository.findByPhotoIdAndIsApprovedTrueOrderByCreatedAtDesc(photo.getId());
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

        List<Comment> comments = commentRepository.findByPhotoIdAndIsApprovedTrueOrderByCreatedAtDesc(photoId);

        String publicUrl = storageService.generatePublicUrl(approvedPhoto.getStorageKey());
        PhotoResponseDTO response = PhotoResponseDTO.fromEntity(approvedPhoto, publicUrl, comments);
        sseBroadcaster.broadcastPhotoApproved(photo.getEvent().getId(), response);

        return response;
    }

    @Override
    @Transactional
    public void rejectPhoto(UUID photoId) {
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new ResourceNotFoundException("No se encontró la fotografía con ID: " + photoId));

        UUID eventId = photo.getEvent().getId();
        String storageKey = photo.getStorageKey();

        // 1. Eliminar primero el objeto en almacenamiento (Cloudflare R2 o local)
        try {
            storageService.deleteFile(storageKey);
        } catch (Exception e) {
            log.error("Error al eliminar objeto '{}' del storage para la foto {}: {}", storageKey, photoId, e.getMessage());
            // No detenemos el flujo para asegurar que el registro de la BD sea limpiado si el almacenamiento responde con error
        }

        // 2. Eliminar registro en BD (los comentarios asociados se eliminan en cascada)
        photoRepository.delete(photo);
        log.info("Fotografía con ID {} rechazada y eliminada de R2/Storage y BD por administración", photoId);

        // 3. Notificar en tiempo real vía SSE
        sseBroadcaster.broadcastPhotoRejected(eventId, photoId);
    }

    @Override
    @Transactional
    public void deletePhoto(UUID photoId) {
        rejectPhoto(photoId);
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
            List<Comment> comments = commentRepository.findByPhotoIdAndIsApprovedTrueOrderByCreatedAtDesc(saved.getId());
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

    @Override
    @Transactional(readOnly = true)
    public String generateDownloadUrl(UUID photoId) {
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new ResourceNotFoundException("No se encontró la fotografía con ID: " + photoId));

        if (!photo.isApproved()) {
            throw new ResourceNotFoundException("La fotografía especificada no está aprobada para su descarga.");
        }

        return storageService.generateDownloadUrl(photo.getStorageKey());
    }

    @Override
    @Transactional(readOnly = true)
    public void streamPhotosZip(String slug, List<UUID> photoIds, OutputStream outputStream) {
        Event event = eventService.getEventEntityBySlug(slug);

        List<Photo> photosToZip;
        if (photoIds != null && !photoIds.isEmpty()) {
            photosToZip = photoRepository.findByIdInAndIsApprovedTrue(photoIds);
        } else {
            photosToZip = photoRepository.findByEventIdAndIsApprovedTrue(event.getId());
        }

        if (photosToZip.isEmpty()) {
            log.info("No se encontraron fotografías aprobadas para empaquetar en el archivo ZIP del evento '{}'", slug);
        }

        Set<String> usedEntryNames = new HashSet<>();

        try (ZipOutputStream zos = new ZipOutputStream(outputStream)) {
            for (Photo photo : photosToZip) {
                String entryName = buildZipEntryName(photo, usedEntryNames);
                ZipEntry zipEntry = new ZipEntry(entryName);
                zos.putNextEntry(zipEntry);

                try (InputStream is = storageService.streamObject(photo.getStorageKey())) {
                    is.transferTo(zos);
                } catch (Exception e) {
                    log.error("Error al transmitir la foto ID {} ('{}') al archivo ZIP: {}", photo.getId(), photo.getStorageKey(), e.getMessage());
                }

                zos.closeEntry();
            }
            zos.finish();
            log.info("ZIP streaming completado exitosamente con {} fotografías aprobadas para el evento '{}'", photosToZip.size(), slug);
        } catch (IOException e) {
            log.error("Error de E/S al generar la transmisión del archivo ZIP: {}", e.getMessage(), e);
            throw new StorageException("Error al generar la descarga del archivo ZIP", e);
        }
    }

    private String buildZipEntryName(Photo photo, Set<String> usedNames) {
        String shortId = photo.getId().toString().substring(0, 8);
        String uploader = photo.getUploaderName() != null && !photo.getUploaderName().isBlank()
                ? photo.getUploaderName().replaceAll("[^a-zA-Z0-9_-]", "_")
                : "Invitado";

        String ext = ".jpg";
        if (photo.getStorageKey() != null && photo.getStorageKey().contains(".")) {
            ext = photo.getStorageKey().substring(photo.getStorageKey().lastIndexOf("."));
        }

        String baseName = String.format("%s_%s%s", shortId, uploader, ext);
        String finalName = baseName;
        int counter = 1;
        while (usedNames.contains(finalName)) {
            finalName = String.format("%s_%s_%d%s", shortId, uploader, counter++, ext);
        }
        usedNames.add(finalName);
        return finalName;
    }

    /**
     * Lee los primeros bytes del objeto ya subido a storage y verifica su firma
     * binaria real contra los formatos de imagen permitidos. Si no coincide con
     * ninguna firma válida, elimina el objeto de storage (no deja basura en el
     * bucket) y rechaza con InvalidFileContentException (422) antes de que
     * confirmUpload cree el registro Photo.
     */
    private void validateUploadedFileSignature(String key) {
        byte[] header;
        try (InputStream is = storageService.streamObject(key)) {
            header = is.readNBytes(12);
        } catch (IOException e) {
            log.error("Error al leer los bytes del objeto '{}' para validar su firma: {}", key, e.getMessage());
            throw new StorageException("No se pudo leer el archivo subido para validar su contenido", e);
        }

        if (!FileSignatureValidator.isValidImageSignature(header)) {
            log.warn("Confirmación rechazada: la firma binaria del objeto '{}' no corresponde a una imagen válida. Eliminando de storage.", key);
            try {
                storageService.deleteFile(key);
            } catch (Exception e) {
                log.error("No se pudo eliminar el objeto inválido '{}' del storage tras rechazar su firma: {}", key, e.getMessage());
            }
            throw new InvalidFileContentException(
                    "El archivo subido no corresponde a una imagen válida (JPEG, PNG, WEBP o HEIC). La subida fue rechazada."
            );
        }
    }

    private byte[] headerOf(byte[] bytes) {
        return bytes.length > 12 ? Arrays.copyOf(bytes, 12) : bytes;
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
