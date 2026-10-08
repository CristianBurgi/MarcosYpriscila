package com.tuapp.eventfoto.moderation;

import com.tuapp.eventfoto.common.config.DeletionActor;
import com.tuapp.eventfoto.message.MessageRepository;
import com.tuapp.eventfoto.message.MessageService;
import com.tuapp.eventfoto.moderation.dto.ModeratorMessageDTO;
import com.tuapp.eventfoto.moderation.dto.ModeratorPage;
import com.tuapp.eventfoto.moderation.dto.ModeratorPhotoDTO;
import com.tuapp.eventfoto.photo.PhotoRepository;
import com.tuapp.eventfoto.photo.PhotoService;
import com.tuapp.eventfoto.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Lo que el moderador puede hacer: listar y borrar fotos y mensajes de UN evento (el que ya resolvió
 * EventAccessInterceptor). El borrado delega en los MISMOS servicios que el panel (storage -> base -> SSE,
 * con findByIdAndEventId y la guarda de prefijo de clave); solo cambia el actor del log.
 */
@Service
@RequiredArgsConstructor
public class ModeratorContentService {

    public static final int MAX_PAGE_SIZE = 100;

    private final PhotoRepository photoRepository;
    private final MessageRepository messageRepository;
    private final PhotoService photoService;
    private final MessageService messageService;
    private final StorageService storageService;

    @Transactional(readOnly = true)
    public ModeratorPage<ModeratorPhotoDTO> listPhotos(UUID eventId, int page, int size) {
        Page<ModeratorPhotoDTO> result = photoRepository
                .findByEventIdOrderByCreatedAtDescIdDesc(eventId, pageRequest(page, size))
                .map(photo -> new ModeratorPhotoDTO(photo.getId(), storageService.generatePublicUrl(photo.getStorageKey()),
                        photo.getUploaderName(), photo.getCreatedAt()));
        return new ModeratorPage<>(result.getContent(), result.hasNext());
    }

    @Transactional(readOnly = true)
    public ModeratorPage<ModeratorMessageDTO> listMessages(UUID eventId, int page, int size) {
        Page<ModeratorMessageDTO> result = messageRepository
                .findByEventIdAndIsApprovedTrueOrderByCreatedAtDescIdDesc(eventId, pageRequest(page, size))
                .map(message -> new ModeratorMessageDTO(message.getId(), message.getAuthorName(), message.getText(), message.getCreatedAt()));
        return new ModeratorPage<>(result.getContent(), result.hasNext());
    }

    public void deletePhoto(UUID eventId, UUID photoId) {
        photoService.deletePhoto(eventId, photoId, DeletionActor.MODERADOR);
    }

    public void deleteMessage(UUID eventId, UUID messageId) {
        messageService.deleteMessage(eventId, messageId, DeletionActor.MODERADOR);
    }

    private static PageRequest pageRequest(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
    }
}
