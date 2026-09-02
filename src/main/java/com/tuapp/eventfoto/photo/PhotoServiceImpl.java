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
import java.util.ArrayList;
import java.util.Arrays;
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
    private final PhotoPersistenceService photoPersistenceService;

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
    public PhotoResponseDTO confirmUpload(String slug, ConfirmUploadRequestDTO request) {
        Event event = eventService.getEventEntityBySlug(slug);
        if (!event.isActive()) {
            throw new EventClosedException("La recepción de fotografías para este evento se encuentra cerrada por los novios.");
        }

        // Verificación de contenido real (magic bytes) del objeto ya subido a storage,
        // independiente del Content-Type que haya declarado el cliente. Si es un HEIC/HEIF
        // real, se convierte acá mismo a JPEG (la mayoria de los navegadores -- Chrome,
        // Firefox, Android -- no pueden decodificar HEIC nativamente; solo Safari/iOS lo
        // soporta). Devuelve la key final a usar (la original, o la nueva .jpg si hubo
        // conversion). Si no es una imagen valida, se elimina de storage y se rechaza antes
        // de crear cualquier registro en BD.
        //
        // IMPORTANTE: esto ocurre FUERA de cualquier transacción de BD. Lee de R2, invoca
        // el proceso externo heif-convert y vuelve a subir/borrar en R2 -- operaciones
        // lentas que no deben retener una conexión de HikariCP ni mantener una transacción
        // abierta. Si algo acá falla, todavía no se tocó la base ni el cupo del invitado.
        String finalKey = validateAndConvertIfNeeded(request.key());

        String publicUrl = storageService.generatePublicUrl(finalKey);

        // Transacción corta (en un bean separado, vía proxy): incremento atómico del cupo
        // + insert de la foto. Si el cupo ya está agotado, se rechaza acá -- el objeto ya
        // está en storage (lo subió el cliente vía la presigned URL antes de /confirm),
        // así que se elimina para no dejar basura.
        PhotoResponseDTO response;
        try {
            response = photoPersistenceService.persistConfirmedPhoto(
                    event, finalKey, request.uploaderName(), request.caption(), request.guestToken(), publicUrl);
        } catch (GuestQuotaExceededException e) {
            log.warn("Cupo agotado al confirmar; eliminando objeto huérfano '{}' de storage", finalKey);
            try {
                storageService.deleteFile(finalKey);
            } catch (Exception cleanupEx) {
                log.error("No se pudo eliminar el objeto huérfano '{}' tras rechazo por cupo: {}", finalKey, cleanupEx.getMessage());
            }
            throw e;
        }

        log.info("Foto confirmada y guardada con ID {} para el evento '{}' (pendiente de aprobación)", response.id(), slug);
        sseBroadcaster.broadcastPhotoPending(event.getId(), response);
        return response;
    }

    @Override
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

        // Todo el trabajo pesado (leer bytes, validar firma, convertir HEIC, subir a R2)
        // ocurre FUERA de transacción. Recién después se abre la transacción corta para
        // incrementar el cupo y persistir la foto.
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.error("Error al procesar los bytes de la imagen subida directamente: {}", e.getMessage(), e);
            throw new StorageException("Error al procesar la imagen en el servidor", e);
        }

        // Verificación de contenido real (magic bytes) antes de subir a storage:
        // el Content-Type multipart declarado por el cliente puede mentir.
        if (!FileSignatureValidator.isValidImageSignature(headerOf(bytes))) {
            log.warn("Subida directa rechazada: la firma binaria del archivo no corresponde a una imagen válida (Content-Type declarado: '{}')", contentType);
            throw new InvalidFileContentException(
                    "El archivo subido no corresponde a una imagen válida (JPEG, PNG, WEBP o HEIC). La subida fue rechazada."
            );
        }

        // Fase 8 - Test 5: convertir HEIC/HEIF a JPEG antes de guardar -- la gran
        // mayoría de navegadores (todo menos Safari/iOS) no pueden decodificar HEIC
        // como <img>, la foto quedaría rota para casi todos los invitados.
        //
        // La decisión se toma sobre los BYTES REALES ya leídos para la validación de
        // firma (arriba), no sobre la extensión del filename ni el Content-Type
        // declarado: un iPhone puede mandar un .heic cuyo contenido real ya es JPEG
        // (HEIC nombrado pero transcodificado), y en ese caso no hay que convertir nada.
        boolean heicByBytes = FileSignatureValidator.isHeicSignature(headerOf(bytes));
        // TEMP diagnóstico HEIC (19/09) -- ver comentario equivalente en validateAndConvertIfNeeded.
        log.info("[HEIC-DECISION][uploadDirect] originalFilename='{}' contentTypeDeclarado='{}' isHeicSignature={} bytes={} primeros12={} -> {}",
                originalFilename, contentType, heicByBytes, bytes.length, java.util.Arrays.toString(headerOf(bytes)),
                heicByBytes ? "se convierte con heif-convert" : "ya es imagen navegable (JPEG/PNG/WEBP), se guarda tal cual");
        if (heicByBytes) {
            log.info("Detectada subida directa HEIC/HEIF: iniciando conversión a JPEG antes de guardar");
            try {
                bytes = storageService.convertHeicToJpeg(bytes);
            } catch (Exception e) {
                log.error("Falló la conversión HEIC->JPEG en upload-direct: {}", e.getMessage());
                throw new StorageException("No se pudo procesar la foto HEIC subida. Por favor, intentá subirla nuevamente.", e);
            }
            contentType = "image/jpeg";
            key = String.format("photos/%s/%s.jpg", slug, UUID.randomUUID());
            log.info("Conversión HEIC->JPEG exitosa en upload-direct, nueva key: '{}'", key);
        }

        storageService.uploadBytes(key, bytes, contentType);

        String publicUrl = storageService.generatePublicUrl(key);

        // Transacción corta: incremento atómico del cupo + insert de la foto. Si el cupo
        // lo rechaza acá (condición de carrera), se elimina el objeto recién subido para
        // no dejar basura en storage sin un registro Photo que lo referencie.
        PhotoResponseDTO response;
        try {
            response = photoPersistenceService.persistConfirmedPhoto(event, key, uploaderName, caption, guestToken, publicUrl);
        } catch (GuestQuotaExceededException e) {
            log.warn("Cupo agotado tras subir a storage en upload-direct; eliminando objeto huérfano '{}'", key);
            try {
                storageService.deleteFile(key);
            } catch (Exception cleanupEx) {
                log.error("No se pudo eliminar el objeto huérfano '{}' tras rechazo por cupo: {}", key, cleanupEx.getMessage());
            }
            throw e;
        }

        log.info("Foto subida de forma directa y guardada en BD con ID {} para el evento '{}'", response.id(), slug);
        sseBroadcaster.broadcastPhotoPending(event.getId(), response);
        return response;
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
     * Lee el objeto ya subido a storage, verifica su firma binaria real contra los
     * formatos de imagen permitidos, y si es HEIC/HEIF lo convierte a JPEG antes de
     * que confirmUpload cree el registro Photo.
     *
     * Fase 8 - Test 5: se detectó que HeicConverter/convertHeicToJpeg() existía
     * completo (con heif-convert instalado en el contenedor Docker) pero nunca se
     * invocaba desde ningún flujo real de subida -- las fotos HEIC quedaban
     * guardadas y servidas tal cual, y la gran mayoría de navegadores (Chrome,
     * Firefox, Android; todo excepto Safari/iOS) no pueden decodificar HEIC como
     * <img>, mostrando una imagen rota para casi todos los invitados.
     *
     * @return la key final a usar para el registro Photo: la misma si no era HEIC,
     *         o la nueva key .jpg si se convirtió (el objeto HEIC original se borra).
     */
    private String validateAndConvertIfNeeded(String key) {
        byte[] fullBytes;
        try (InputStream is = storageService.streamObject(key)) {
            fullBytes = is.readAllBytes();
        } catch (IOException e) {
            log.error("Error al leer los bytes del objeto '{}' para validar su firma: {}", key, e.getMessage());
            throw new StorageException("No se pudo leer el archivo subido para validar su contenido", e);
        }

        if (!FileSignatureValidator.isValidImageSignature(headerOf(fullBytes))) {
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

        // Decisión sobre el CONTENIDO REAL ya leído (fullBytes), no sobre la extensión
        // de la key: si el objeto que subió el iPhone se llama .heic pero sus bytes ya
        // son JPEG/PNG/WEBP válidos, isValidImageSignature() lo aceptó arriba y acá lo
        // dejamos pasar tal cual -- no se invoca heif-convert sobre algo que no es HEIC.
        boolean heicByBytes = FileSignatureValidator.isHeicSignature(headerOf(fullBytes));
        // TEMP diagnóstico HEIC (19/09) -- deja rastro explícito de por qué camino pasó
        // cada foto. Quitar (o bajar a debug) una vez verificado el flujo con iPhone real.
        log.info("[HEIC-DECISION][confirmUpload] key='{}' isHeicSignature={} bytesLeidos={} primeros12={} -> {}",
                key, heicByBytes, fullBytes.length, java.util.Arrays.toString(headerOf(fullBytes)),
                heicByBytes ? "se convierte con heif-convert" : "ya es imagen navegable (JPEG/PNG/WEBP), se guarda tal cual");
        if (!heicByBytes) {
            return key;
        }

        log.info("Detectado contenido HEIC/HEIF real en '{}': iniciando conversión a JPEG antes de confirmar la foto", key);
        byte[] jpegBytes;
        try {
            jpegBytes = storageService.convertHeicToJpeg(fullBytes);
        } catch (Exception e) {
            log.error("Falló la conversión HEIC->JPEG para '{}': {}", key, e.getMessage());
            try {
                storageService.deleteFile(key);
            } catch (Exception cleanupEx) {
                log.error("No se pudo eliminar el objeto HEIC '{}' tras fallar la conversión: {}", key, cleanupEx.getMessage());
            }
            throw new StorageException("No se pudo procesar la foto HEIC subida. Por favor, intentá subirla nuevamente.", e);
        }

        String jpegKey = siblingJpegKey(key);
        storageService.uploadBytes(jpegKey, jpegBytes, "image/jpeg");
        try {
            storageService.deleteFile(key);
        } catch (Exception e) {
            log.warn("No se pudo eliminar el HEIC original '{}' tras convertirlo a JPEG: {}", key, e.getMessage());
        }
        log.info("Conversión HEIC->JPEG exitosa: '{}' -> '{}' ({} bytes -> {} bytes)", key, jpegKey, fullBytes.length, jpegBytes.length);
        return jpegKey;
    }

    /**
     * Deriva una key .jpg hermana de la key HEIC original, dentro del mismo prefijo del
     * evento pero con un UUID nuevo. Usar un UUID nuevo (en vez de solo cambiar la
     * extensión) evita colisionar con la key original cuando el objeto HEIC venía
     * nombrado {@code .jpg} -- en ese caso {@code base + ".jpg"} sería la misma key y el
     * posterior deleteFile(original) borraría el objeto recién convertido.
     */
    private String siblingJpegKey(String heicKey) {
        int lastSlash = heicKey.lastIndexOf('/');
        String prefix = lastSlash >= 0 ? heicKey.substring(0, lastSlash + 1) : "";
        return prefix + UUID.randomUUID() + ".jpg";
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
