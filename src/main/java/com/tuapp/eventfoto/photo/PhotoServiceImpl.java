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
import com.tuapp.eventfoto.message.GuestbookPdfService;
import com.tuapp.eventfoto.photo.dto.ConfirmUploadRequestDTO;
import com.tuapp.eventfoto.photo.dto.PhotoResponseDTO;
import com.tuapp.eventfoto.photo.dto.UploadUrlRequestDTO;
import com.tuapp.eventfoto.photo.dto.UploadUrlResponseDTO;
import com.tuapp.eventfoto.realtime.SseBroadcaster;
import com.tuapp.eventfoto.storage.FileSignatureValidator;
import com.tuapp.eventfoto.storage.StorageKeys;
import com.tuapp.eventfoto.storage.StorageService;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tuapp.eventfoto.common.exception.InvalidFileFormatException;
import com.tuapp.eventfoto.common.exception.StorageException;
import com.tuapp.eventfoto.common.exception.UploadClaimFailedException;
import com.tuapp.eventfoto.common.exception.UploadInProgressException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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
    private final GuestbookPdfService guestbookPdfService;
    private final PhotoUploadClaimService photoUploadClaimService;
    private final AlbumReader albumReader;

    @Override
    public UploadUrlResponseDTO generateUploadUrl(String slug, UploadUrlRequestDTO request, String clientIp, String guestToken) {
        // 1. Aplicar rate limiting en dos capas: por guestToken (primario) + por IP (secundario)
        rateLimiterService.checkUploadUrlRateLimit(clientIp, guestToken);

        // 2. Validar que el evento exista por slug y esté activo
        Event event = eventService.getEventEntityBySlug(slug);
        if (!event.isActive()) {
            throw new EventClosedException("La recepción de fotografías para este evento se encuentra cerrada por el organizador.");
        }

        // 3. Chequeo previo (no atómico) del cupo del invitado: evita gastar una presigned
        // URL si ya sabemos que no tiene fotos disponibles. La verdad final y atómica se
        // aplica en confirmUpload() vía GuestQuotaService.incrementUsageOrThrow().
        guestQuotaService.assertQuotaAvailable(event.getId(), request.guestToken());

        String contentType = request.contentType();
        String filename = request.filename();

        String extension = StorageKeys.validatedExtension(filename, contentType);
        String key = StorageKeys.newKey(event.getId(), extension);

        String presignedUrl = storageService.generateUploadUrl(key, contentType);
        String publicUrl = storageService.generatePublicUrl(key);

        return new UploadUrlResponseDTO(presignedUrl, key, publicUrl);
    }

    @Override
    public PhotoResponseDTO confirmUpload(String slug, ConfirmUploadRequestDTO request) {
        Event event = eventService.getEventEntityBySlug(slug);

        // La clave tiene que ser de ESTE evento (events/{eventId}/{uuid}.ext). Se valida ANTES de
        // reclamarla y de tocar storage o base: así la respuesta es la misma exista o no la clave
        // en otro evento, y una clave ajena nunca crea una reclamación ni una foto que apunte al
        // objeto de otro evento.
        if (!StorageKeys.belongsToEvent(event.getId(), request.key())) {
            throw new InvalidFileFormatException("La clave de la subida no es válida para este evento.");
        }

        if (!event.isActive()) {
            throw new EventClosedException("La recepción de fotografías para este evento se encuentra cerrada por el organizador.");
        }

        // Idempotencia (Fase 9.0 - Bloque C): el frontend puede llamar /confirm más de una
        // vez con la MISMA upload_key (la key de la presigned URL) si la respuesta de un
        // intento anterior se perdió por la red -- el PUT a R2 ya había terminado, no hace
        // falta ni es seguro repetir todo desde cero. Se reclama ANTES de tocar storage/
        // HEIC/cupo: ver PhotoUploadClaimService para el porqué de la transacción separada
        // (evita bloquear al perdedor de una carrera concurrente durante toda la conversión).
        boolean claimed;
        try {
            photoUploadClaimService.claimOrThrow(request.key());
            claimed = true;
        } catch (DataIntegrityViolationException e) {
            claimed = false;
        }

        if (!claimed) {
            return resolveAlreadyClaimedConfirm(event.getId(), request.key());
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
        String finalKey;
        try {
            finalKey = validateAndConvertIfNeeded(request.key());
        } catch (RuntimeException e) {
            photoUploadClaimService.markFailed(request.key(), statusOf(e), e.getMessage());
            throw e;
        }

        String publicUrl = storageService.generatePublicUrl(finalKey);

        // Transacción corta (en un bean separado, vía proxy): incremento atómico del cupo
        // + insert de la foto. Si el cupo ya está agotado, se rechaza acá -- el objeto ya
        // está en storage (lo subió el cliente vía la presigned URL antes de /confirm),
        // así que se elimina para no dejar basura.
        PhotoResponseDTO response;
        try {
            response = photoPersistenceService.persistConfirmedPhoto(
                    event, request.key(), finalKey, request.uploaderName(), request.caption(), request.guestToken(), publicUrl);
        } catch (GuestQuotaExceededException e) {
            log.warn("Cupo agotado al confirmar; eliminando objeto huérfano '{}' de storage", finalKey);
            try {
                storageService.deleteFile(finalKey);
            } catch (Exception cleanupEx) {
                log.error("No se pudo eliminar el objeto huérfano '{}' tras rechazo por cupo: {}", finalKey, cleanupEx.getMessage());
            }
            photoUploadClaimService.markFailed(request.key(), HttpStatus.FORBIDDEN.value(), e.getMessage());
            throw e;
        }

        log.info("Foto confirmada y publicada con ID {} para el evento '{}'", response.id(), slug);
        sseBroadcaster.broadcastPhotoPublished(event.getId(), response);
        return response;
    }

    /**
     * Otra llamada con la misma upload_key ya la reclamó (reintento propio o una carrera
     * concurrente real). Si esa llamada ya terminó de persistir la foto, se devuelve tal
     * cual (idempotencia), sin volver a tocar storage ni cupo. Si terminó en un rechazo
     * definitivo (archivo inválido, cupo agotado), se le reconstruye al caller el MISMO
     * status y mensaje. Si todavía está procesando, se lanza una excepción transitoria
     * (503) que el propio mecanismo de reintento del frontend resuelve en su próximo
     * intento, sin bloquear este hilo/conexión esperando a que termine.
     */
    PhotoResponseDTO resolveAlreadyClaimedConfirm(UUID eventId, String uploadKey) { // package-private: lo prueba un test
        // Acotado al evento: la foto de otro evento nunca se devuelve por esta vía.
        Optional<Photo> existing = photoRepository.findByUploadKeyAndEventId(uploadKey, eventId);
        if (existing.isPresent()) {
            Photo photo = existing.get();
            log.info("confirmUpload idempotente: la upload_key '{}' ya fue procesada, devolviendo foto {}", uploadKey, photo.getId());
            List<Comment> comments = commentRepository.findByPhotoIdAndIsApprovedTrueOrderByCreatedAtDesc(photo.getId());
            return PhotoResponseDTO.fromEntity(photo, storageService.generatePublicUrl(photo.getStorageKey()), comments);
        }

        Optional<PhotoUploadClaim> claim = photoUploadClaimService.find(uploadKey);
        if (claim.isPresent() && claim.get().getFailedStatus() != null) {
            throw new UploadClaimFailedException(claim.get().getFailedStatus(), claim.get().getFailedMessage());
        }

        throw new UploadInProgressException("Tu foto se está procesando, esperá un instante.");
    }

    private static int statusOf(RuntimeException e) {
        if (e instanceof InvalidFileContentException) {
            return HttpStatus.UNPROCESSABLE_ENTITY.value();
        }
        return HttpStatus.INTERNAL_SERVER_ERROR.value();
    }

    @Override
    public PhotoResponseDTO uploadDirect(String slug, org.springframework.web.multipart.MultipartFile file, String uploaderName, String caption, String guestToken) {
        if (file == null || file.isEmpty()) {
            throw new InvalidFileFormatException("El archivo enviado está vacío.");
        }

        Event event = eventService.getEventEntityBySlug(slug);
        if (!event.isActive()) {
            throw new EventClosedException("La recepción de fotografías para este evento se encuentra cerrada por el organizador.");
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
        // Extensión permitida y coherente con el content-type declarado (400 si no).
        String extension = StorageKeys.validatedExtension(originalFilename, file.getContentType());

        String key = StorageKeys.newKey(event.getId(), extension);

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
        // Diagnóstico HEIC (19/09) en nivel debug -- ver comentario equivalente en validateAndConvertIfNeeded.
        log.debug("[HEIC-DECISION][uploadDirect] originalFilename='{}' contentTypeDeclarado='{}' isHeicSignature={} bytes={} primeros12={} -> {}",
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
            key = StorageKeys.newKey(event.getId(), ".jpg");
            log.info("Conversión HEIC->JPEG exitosa en upload-direct, nueva key: '{}'", key);
        }

        storageService.uploadBytes(key, bytes, contentType);

        String publicUrl = storageService.generatePublicUrl(key);

        // Transacción corta: incremento atómico del cupo + insert de la foto. Si el cupo
        // lo rechaza acá (condición de carrera), se elimina el objeto recién subido para
        // no dejar basura en storage sin un registro Photo que lo referencie.
        PhotoResponseDTO response;
        try {
            response = photoPersistenceService.persistConfirmedPhoto(event, key, key, uploaderName, caption, guestToken, publicUrl);
        } catch (GuestQuotaExceededException e) {
            log.warn("Cupo agotado tras subir a storage en upload-direct; eliminando objeto huérfano '{}'", key);
            try {
                storageService.deleteFile(key);
            } catch (Exception cleanupEx) {
                log.error("No se pudo eliminar el objeto huérfano '{}' tras rechazo por cupo: {}", key, cleanupEx.getMessage());
            }
            throw e;
        }

        log.info("Foto subida de forma directa y publicada con ID {} para el evento '{}'", response.id(), slug);
        sseBroadcaster.broadcastPhotoPublished(event.getId(), response);
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PhotoResponseDTO> getPhotos(String slug, Pageable pageable) {
        Event event = eventService.getEventEntityBySlug(slug);
        return photoRepository.findByEventIdOrderByCreatedAtDesc(event.getId(), pageable)
                .map(photo -> {
                    List<Comment> comments = commentRepository.findByPhotoIdAndIsApprovedTrueOrderByCreatedAtDesc(photo.getId());
                    return PhotoResponseDTO.fromEntity(photo, storageService.generatePublicUrl(photo.getStorageKey()), comments);
                });
    }

    /**
     * Único control de moderación de fotos: elimina primero el objeto de storage y
     * después el registro en BD, y notifica PHOTO_DELETED por SSE para que el álbum y
     * la pantalla del salón la saquen al instante.
     */
    @Override
    @Transactional
    public void deletePhoto(UUID eventId, UUID photoId) {
        Photo photo = photoRepository.findByIdAndEventId(photoId, eventId)
                .orElseThrow(() -> new ResourceNotFoundException("No se encontró la fotografía con ID: " + photoId));

        String storageKey = photo.getStorageKey();

        // 1. Eliminar primero el objeto en almacenamiento (Cloudflare R2 o local).
        // Guarda (defensa en profundidad): solo se borra un objeto que está bajo events/{eventId}/.
        // Si la clave apunta a otro evento no se toca el objeto (podría ser de otro organizador),
        // se deja un WARN y se borra igual la fila de la base.
        if (StorageKeys.hasEventPrefix(eventId, storageKey)) {
            try {
                storageService.deleteFile(storageKey);
            } catch (Exception e) {
                log.error("Error al eliminar objeto '{}' del storage para la foto {}: {}", storageKey, photoId, e.getMessage());
                // No detenemos el flujo para asegurar que el registro de la BD sea limpiado si el almacenamiento responde con error
            }
        } else {
            log.warn("La foto {} del evento {} apunta a la clave '{}', que no está bajo '{}': NO se borra el objeto de storage, solo la fila de la base",
                    photoId, eventId, storageKey, StorageKeys.prefixOf(eventId));
        }

        // 2. Eliminar registro en BD (los comentarios asociados se eliminan en cascada)
        photoRepository.delete(photo);
        log.info("Fotografía con ID {} eliminada de R2/Storage y BD por administración", photoId);

        // 3. Notificar en tiempo real vía SSE
        sseBroadcaster.broadcastPhotoDeleted(eventId, photoId);
    }

    @Override
    @Transactional(readOnly = true)
    public long countTotalPhotos(String slug) {
        Event event = eventService.getEventEntityBySlug(slug);
        return photoRepository.countByEventId(event.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public String generateDownloadUrl(UUID eventId, UUID photoId) {
        Photo photo = photoRepository.findByIdAndEventId(photoId, eventId)
                .orElseThrow(() -> new ResourceNotFoundException("No se encontró la fotografía con ID: " + photoId));

        return storageService.generateDownloadUrl(photo.getStorageKey());
    }

    /**
     * SIN @Transactional a propósito: el ZIP puede tardar minutos (render del PDF, lectura de
     * cada foto desde R2) y una transacción abierta sostendría una conexión del pool todo ese
     * tiempo. Con varios organizadores bajando álbumes a la vez se agotaría el pool. La lectura
     * de la base ocurre en AlbumReader (transacción corta, ya cerrada cuando empieza el
     * streaming); GuestbookPdfService hace lo mismo con su lectura.
     */
    @Override
    public void streamPhotosZip(String slug, List<UUID> photoIds, OutputStream outputStream) {
        List<AlbumReader.ZipPhoto> photosToZip = albumReader.loadPhotos(slug, photoIds);

        if (photosToZip.isEmpty()) {
            log.info("No se encontraron fotografías para empaquetar en el archivo ZIP del evento '{}'", slug);
        }

        Set<String> usedEntryNames = new HashSet<>();

        // Álbum completo (sin selección): el libro de visitas va en la raíz del ZIP. Se genera
        // ANTES de empezar a escribir: si falla, el ZIP de fotos sale igual y el error va a
        // Sentry -- un problema con el PDF no puede impedir que el organizador baje sus fotos.
        byte[] guestbookPdf = null;
        if (photoIds == null || photoIds.isEmpty()) {
            try {
                guestbookPdf = guestbookPdfService.generate(slug);
            } catch (Exception e) {
                log.error("No se pudo generar el libro de visitas para el ZIP del evento '{}': {}", slug, e.getMessage(), e);
                Sentry.captureException(e);
            }
        }

        int failedPhotos = 0;
        try (ZipOutputStream zos = new ZipOutputStream(outputStream)) {
            if (guestbookPdf != null) {
                usedEntryNames.add(GuestbookPdfService.FILENAME);
                zos.putNextEntry(new ZipEntry(GuestbookPdfService.FILENAME));
                zos.write(guestbookPdf);
                zos.closeEntry();
            }
            for (AlbumReader.ZipPhoto photo : photosToZip) {
                String entryName = buildZipEntryName(photo, usedEntryNames);
                zos.putNextEntry(new ZipEntry(entryName));
                if (!copyPhotoIntoZip(photo, zos)) {
                    failedPhotos++;
                }
                zos.closeEntry();
            }
            zos.finish();
            log.info("ZIP streaming completado con {} fotografías ({} con error de lectura en storage) para el evento '{}'",
                    photosToZip.size(), failedPhotos, slug);
        } catch (IOException e) {
            // Todas las lecturas de storage se manejan dentro de copyPhotoIntoZip(): una
            // IOException acá solo puede venir de ESCRIBIR al cliente (canceló la descarga,
            // perdió señal). No hay a quién responder ni es un bug: no va a Sentry.
            log.info("Descarga ZIP del evento '{}' interrumpida por el cliente: {}", slug, e.getMessage());
        }
    }

    /**
     * Copia una foto de storage al ZIP separando las dos puntas del stream:
     * - Falla LEYENDO de storage (R2 caído, conexión reseteada por R2, objeto faltante):
     *   error real -> Sentry, y se sigue con la próxima foto para no perder el resto del
     *   álbum. La entrada de esa foto queda vacía o incompleta.
     * - Falla ESCRIBIENDO al ZIP (el cliente cortó): se propaga la IOException para
     *   abortar la descarga completa.
     *
     * Antes (is.transferTo(zos) dentro de un catch Exception) las dos fallas se mezclaban
     * y solo se hacía log.error, que NO llega a Sentry (no hay integración con logback).
     *
     * @return false si hubo una falla de lectura en storage.
     */
    private boolean copyPhotoIntoZip(AlbumReader.ZipPhoto photo, ZipOutputStream zos) throws IOException {
        InputStream is;
        try {
            is = storageService.streamObject(photo.storageKey());
        } catch (Exception e) {
            reportZipStorageFailure(photo, e);
            return false;
        }
        try {
            byte[] buffer = new byte[8192];
            while (true) {
                int read;
                try {
                    read = is.read(buffer);
                } catch (IOException e) {
                    reportZipStorageFailure(photo, e);
                    return false;
                }
                if (read == -1) {
                    return true;
                }
                zos.write(buffer, 0, read); // IOException acá = cliente: se propaga
            }
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                log.debug("No se pudo cerrar el stream de storage de la foto {}: {}", photo.id(), e.getMessage());
            }
        }
    }

    private void reportZipStorageFailure(AlbumReader.ZipPhoto photo, Exception e) {
        log.error("Error leyendo de storage la foto ID {} ('{}') para el ZIP: {}", photo.id(), photo.storageKey(), e.getMessage(), e);
        Sentry.captureException(e);
    }

    private String buildZipEntryName(AlbumReader.ZipPhoto photo, Set<String> usedNames) {
        String shortId = photo.id().toString().substring(0, 8);
        String uploader = photo.uploaderName() != null && !photo.uploaderName().isBlank()
                ? photo.uploaderName().replaceAll("[^a-zA-Z0-9_-]", "_")
                : "Invitado";

        String ext = ".jpg";
        if (photo.storageKey() != null && photo.storageKey().contains(".")) {
            ext = photo.storageKey().substring(photo.storageKey().lastIndexOf("."));
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
        // Diagnóstico HEIC (19/09) -- deja rastro explícito de por qué camino pasó cada
        // foto. En debug desde la Fase 9.0: activarlo con logging.level.com.tuapp.eventfoto.photo=DEBUG.
        log.debug("[HEIC-DECISION][confirmUpload] key='{}' isHeicSignature={} bytesLeidos={} primeros12={} -> {}",
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

}
