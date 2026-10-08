package com.tuapp.eventfoto.demo;

import com.tuapp.eventfoto.common.exception.ContentModerationException;
import com.tuapp.eventfoto.common.exception.FileTooLargeException;
import com.tuapp.eventfoto.common.exception.InvalidEventSettingsException;
import com.tuapp.eventfoto.common.exception.InvalidFileFormatException;
import com.tuapp.eventfoto.common.moderation.ContentModerationService;
import com.tuapp.eventfoto.event.EventPalette;
import com.tuapp.eventfoto.message.dto.CreateMessageRequestDTO;
import com.tuapp.eventfoto.message.dto.MessageResponseDTO;
import com.tuapp.eventfoto.photo.dto.PhotoResponseDTO;
import com.tuapp.eventfoto.realtime.SseBroadcaster;
import com.tuapp.eventfoto.storage.ImageContent;
import com.tuapp.eventfoto.storage.StorageKeys;
import com.tuapp.eventfoto.storage.StorageService;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

/**
 * Demo en vivo (Fase 9.7-B). No es un Event: tiene sus tablas (V19), sus claves en R2 (demo/{sid}/) y su canal SSE,
 * así que no pasa por la ventana de subida, la purga de la 9.6, el listado del superadmin ni "Mis eventos".
 * Reusa las mismas piezas que un evento real para validar lo que llega: tope de tamaño, extensión, firma binaria,
 * HEIC -> JPEG, filtro de contenido y largos de los mensajes. Además le saca los metadatos a todo JPEG.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoService {

    public static final Duration TTL = Duration.ofMinutes(30);
    public static final int MAX_PHOTOS = 5;
    public static final int MAX_MESSAGES = 5;
    static final String PHOTO_LIMIT_MESSAGE = "En la demo podés subir hasta 5 fotos. ¡En tu evento no hay límite!";
    static final String MESSAGE_LIMIT_MESSAGE = "En la demo podés escribir hasta 5 mensajes. ¡En tu evento no hay límite!";
    static final String ENDED_MESSAGE = "Tu demo terminó.";
    /** Crédito de las fotos del visitante en la pantalla: "Subida por vos". */
    static final String VISITOR_NAME = "vos";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DemoSessionRepository sessions;
    private final DemoPhotoRepository photos;
    private final DemoMessageRepository messages;
    private final StorageService storageService;
    private final ImageContent imageContent;
    private final ContentModerationService contentModerationService;
    private final SseBroadcaster sseBroadcaster;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;

    @Value("${app.upload.max-file-bytes}")
    private long maxFileBytes;

    public record Stats(long activeSessions, long photos) {
    }

    public record PurgeResult(int sessions, int objects, int failed) {
    }

    // --- Sesión ---

    /** 32 bytes de SecureRandom en base64url: 43 caracteres, el formato de StorageKeys.DEMO_SID. */
    static String newSid() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static boolean isValidSid(String sid) {
        return sid != null && StorageKeys.DEMO_SID.matcher(sid).matches();
    }

    /** La demo, si el sid tiene el formato, existe y no pasaron 30 minutos. El formato se valida antes de ir a la base. */
    public Optional<DemoSession> findActive(String sid) {
        if (!isValidSid(sid)) {
            return Optional.empty();
        }
        return sessions.findById(sid).filter(s -> clock.instant().isBefore(expiresAt(s)));
    }

    public DemoSession requireActive(String sid) {
        return findActive(sid).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ENDED_MESSAGE));
    }

    public static Instant expiresAt(DemoSession session) {
        return session.getCreatedAt().plus(TTL);
    }

    /**
     * Fin del wizard: valida y convierte la imagen en memoria, la sube a R2 fuera de transacción y recién después
     * inserta la fila (una sola sentencia). Si el insert falla, se borra el objeto: no quedan huérfanos.
     */
    public DemoSession create(LocalDate eventDate, String rawColor, MultipartFile image) {
        if (eventDate == null) {
            throw new InvalidEventSettingsException("Elegí la fecha del evento.");
        }
        String color;
        try {
            color = EventPalette.normalize(rawColor == null ? null : rawColor.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidEventSettingsException("El color tiene que tener el formato #rrggbb.");
        }
        String sid = newSid();

        String backgroundKey = null;
        if (image != null && !image.isEmpty()) {
            byte[] jpeg = ImageContent.stripJpegMetadata(
                    imageContent.validateAndConvert(readChecked(image), "demo-branding").bytes());
            backgroundKey = StorageKeys.newDemoBrandingKey(sid);
            storageService.uploadBytes(backgroundKey, jpeg, "image/jpeg");
        }

        DemoSession session = DemoSession.builder()
                .sid(sid).color(color).backgroundKey(backgroundKey).eventDate(eventDate).createdAt(clock.instant()).build();
        try {
            sessions.saveAndFlush(session);
        } catch (RuntimeException e) {
            deleteQuietly(backgroundKey);
            throw e;
        }
        log.info("Demo {} creada (fondo: {})", shortSid(sid), backgroundKey != null);
        return session;
    }

    public String backgroundUrl(DemoSession session) {
        return session.getBackgroundKey() == null ? null : storageService.generatePublicUrl(session.getBackgroundKey());
    }

    // --- Fotos ---

    /** Mismo camino que upload-direct: chequeo previo del tope, R2, insert con slot y, si el insert pierde, borrar el objeto. */
    public PhotoResponseDTO uploadPhoto(DemoSession session, MultipartFile file) {
        String sid = session.getSid();
        if (photos.countBySid(sid) >= MAX_PHOTOS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, PHOTO_LIMIT_MESSAGE);
        }
        String contentType = file == null ? null : file.getContentType();
        if (contentType == null || contentType.isBlank() || contentType.equalsIgnoreCase("image/jpg")) {
            contentType = "image/jpeg";
        }
        byte[] bytes = readChecked(file);
        String extension = StorageKeys.validatedExtension(file.getOriginalFilename(), file.getContentType());

        ImageContent.Normalized image = imageContent.validateAndConvert(bytes, "demo-upload");
        bytes = image.bytes();
        if (image.convertedFromHeic()) {
            contentType = "image/jpeg";
            extension = ".jpg";
        }
        // ponytail: solo JPEG (casi todo: el navegador recodifica a JPEG y el HEIC se convierte). Un PNG o WebP que
        // llegue tal cual conserva sus metadatos; recodificarlo en el servidor si alguna vez importa.
        if (ImageContent.isJpeg(bytes)) {
            bytes = ImageContent.stripJpegMetadata(bytes);
        }

        String key = StorageKeys.newDemoKey(sid, extension);
        storageService.uploadBytes(key, bytes, contentType);

        DemoPhoto saved;
        try {
            saved = insertInSlot(MAX_PHOTOS, PHOTO_LIMIT_MESSAGE, () -> photos.countBySid(sid),
                    slot -> photos.saveAndFlush(DemoPhoto.builder()
                            .sid(sid).slot((short) slot).objectKey(key).createdAt(clock.instant()).build()));
        } catch (RuntimeException e) {
            deleteQuietly(key);
            throw e;
        }
        PhotoResponseDTO dto = toDto(saved);
        sseBroadcaster.broadcastDemoPhoto(sid, dto);
        return dto;
    }

    /** Las del visitante (la más nueva primero) y después las 10 de ejemplo. */
    public List<PhotoResponseDTO> photos(DemoSession session) {
        List<PhotoResponseDTO> all = new ArrayList<>();
        photos.findBySidOrderByCreatedAtDescIdDesc(session.getSid()).forEach(p -> all.add(toDto(p)));
        all.addAll(DemoExamples.PHOTOS);
        return all;
    }

    public long photoCount(DemoSession session) {
        return photos.countBySid(session.getSid());
    }

    private PhotoResponseDTO toDto(DemoPhoto photo) {
        String url = storageService.generatePublicUrl(photo.getObjectKey());
        return new PhotoResponseDTO(photo.getId(), null, photo.getObjectKey(), url, VISITOR_NAME, photo.getCreatedAt(), 0, List.of());
    }

    // --- Libro de visitas ---

    /** Mismo filtro y mismos largos (@Valid en el controller) que addMessage de un evento real. */
    public MessageResponseDTO addMessage(DemoSession session, CreateMessageRequestDTO request) {
        if (!contentModerationService.isAllowed(request.text())) {
            log.warn("Mensaje bloqueado por el filtro de moderación de contenido en la demo {}", shortSid(session.getSid()));
            throw new ContentModerationException("Tu mensaje no pudo publicarse, revisá el contenido e intentá de nuevo.");
        }
        String sid = session.getSid();
        DemoMessage saved = insertInSlot(MAX_MESSAGES, MESSAGE_LIMIT_MESSAGE, () -> messages.countBySid(sid),
                slot -> messages.saveAndFlush(DemoMessage.builder()
                        .sid(sid).slot((short) slot)
                        .authorName(request.authorName().trim()).text(request.text().trim())
                        .createdAt(clock.instant()).build()));
        MessageResponseDTO dto = toDto(saved);
        sseBroadcaster.broadcastDemoMessage(sid, dto);
        return dto;
    }

    /** Los del visitante (el más nuevo primero) y después los de ejemplo. */
    public List<MessageResponseDTO> messages(DemoSession session) {
        List<MessageResponseDTO> all = new ArrayList<>();
        messages.findBySidOrderByCreatedAtDescIdDesc(session.getSid()).forEach(m -> all.add(toDto(m)));
        all.addAll(DemoExamples.TICKER);
        return all;
    }

    public long messageCount(DemoSession session) {
        return messages.countBySid(session.getSid());
    }

    private static MessageResponseDTO toDto(DemoMessage m) {
        return new MessageResponseDTO(m.getId(), null, m.getAuthorName(), m.getText(), true, m.getCreatedAt());
    }

    /**
     * Inserta en el primer slot libre (1..max). Dos requests simultáneas que cuentan lo mismo van al mismo slot: la
     * base rechaza la segunda (unique(sid, slot)), que vuelve a contar y prueba el siguiente o se rechaza con el tope.
     */
    private static <T> T insertInSlot(int max, String limitMessage, LongSupplier count, IntFunction<T> insert) {
        for (int attempt = 0; attempt <= max; attempt++) {
            long used = count.getAsLong();
            if (used >= max) {
                break;
            }
            try {
                return insert.apply((int) used + 1);
            } catch (DataIntegrityViolationException e) {
                log.debug("Slot {} de la demo ocupado por otra request; se vuelve a contar", used + 1);
            }
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, limitMessage);
    }

    // --- Limpieza ---

    /** Borra las demos de más de 30 minutos. Cada una por separado: una que falla no frena al resto y se reintenta. */
    public PurgeResult purgeExpired() {
        return purge(sessions.findExpiredSids(clock.instant().minus(TTL)));
    }

    /** "Vaciar demo": todas las demos y, al final, cualquier resto bajo demo/ (por ejemplo, de un corte a mitad de una subida). */
    public PurgeResult purgeAll() {
        PurgeResult result = purge(sessions.findAll().stream().map(DemoSession::getSid).toList());
        int leftovers = storageService.deleteDemoObjects(null);
        return new PurgeResult(result.sessions(), result.objects() + leftovers, result.failed());
    }

    public Stats stats() {
        return new Stats(sessions.countActive(clock.instant().minus(TTL)), photos.count());
    }

    private PurgeResult purge(List<String> sids) {
        int purged = 0;
        int objects = 0;
        int failed = 0;
        for (String sid : sids) {
            try {
                objects += purgeOne(sid);
                purged++;
            } catch (RuntimeException e) {
                failed++;
                log.error("Demo: falló el borrado de {}; se reintenta en la próxima corrida", shortSid(sid), e);
                Sentry.captureException(e);
            }
        }
        return new PurgeResult(purged, objects, failed);
    }

    private int purgeOne(String sid) {
        // 1. R2 primero, sin transacción: si falla, la base queda intacta y se reintenta.
        int objects = storageService.deleteDemoObjects(sid);
        // 2. La base, en una transacción corta. Hijos antes que la sesión.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            photos.deleteBySid(sid);
            messages.deleteBySid(sid);
            sessions.deleteBySid(sid);
        });
        // 3. Las pantallas abiertas se desconectan; al reconectar reciben 404 y demo.js las lleva a "Tu demo terminó".
        sseBroadcaster.closeDemo(sid);
        return objects;
    }

    // --- Utilidades ---

    private byte[] readChecked(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidFileFormatException("El archivo enviado está vacío.");
        }
        if (file.getSize() > maxFileBytes) {
            throw new FileTooLargeException("El archivo supera el tope de " + maxFileBytes + " bytes");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new InvalidFileFormatException("No pudimos leer la imagen. Probá con otra.");
        }
    }

    private void deleteQuietly(String key) {
        if (key == null) {
            return;
        }
        try {
            storageService.deleteFile(key);
        } catch (RuntimeException e) {
            log.error("Demo: no se pudo borrar el objeto que quedó sin fila; lo borra el vaciado o la limpieza: {}", e.getMessage());
        }
    }

    /** Para los logs: nunca el sid entero (es la llave de la demo). */
    static String shortSid(String sid) {
        return sid == null ? "-" : sid.substring(0, Math.min(6, sid.length())) + "…";
    }
}
