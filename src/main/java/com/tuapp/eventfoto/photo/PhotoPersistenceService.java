package com.tuapp.eventfoto.photo;

import com.tuapp.eventfoto.comment.Comment;
import com.tuapp.eventfoto.comment.CommentRepository;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.photo.dto.PhotoResponseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aísla la ÚNICA parte del alta de una foto que necesita una transacción de BD:
 * el incremento atómico del cupo del invitado + el insert de la foto (y su caption
 * opcional).
 *
 * Está en un bean separado a propósito: así la llamada @Transactional pasa por el
 * proxy de Spring. Una self-invocation desde PhotoServiceImpl (llamar a un método
 * @Transactional de la misma clase) NO abriría transacción.
 *
 * Ninguna operación de storage ni de proceso externo (R2, heif-convert) ocurre acá
 * dentro -- todo eso se hace antes, fuera de transacción, en PhotoServiceImpl.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhotoPersistenceService {

    private final PhotoRepository photoRepository;
    private final CommentRepository commentRepository;
    private final GuestQuotaService guestQuotaService;

    /**
     * Transacción corta. El incremento atómico del cupo va PRIMERO: si el cupo ya está
     * agotado (incluso por una condición de carrera entre pestañas), lanza
     * GuestQuotaExceededException y la transacción se revierte sin haber insertado
     * ninguna foto ni comentario.
     *
     * @param publicUrl URL pública ya resuelta (string puro, sin llamada de red) para
     *                  incluir en la respuesta sin depender de la sesión de Hibernate.
     */
    @Transactional
    public PhotoResponseDTO persistConfirmedPhoto(Event event, String storageKey, String uploaderName,
                                                  String caption, String guestToken, String publicUrl) {
        guestQuotaService.incrementUsageOrThrow(event, guestToken);

        String uploader = (uploaderName != null && !uploaderName.isBlank()) ? uploaderName.trim() : "Invitado";

        Photo photo = Photo.builder()
                .event(event)
                .storageKey(storageKey)
                .uploaderName(uploader)
                .isApproved(false) // Requiere aprobación previa por moderación de admin
                .build();
        Photo savedPhoto = photoRepository.save(photo);

        if (caption != null && !caption.isBlank()) {
            Comment captionComment = Comment.builder()
                    .photo(savedPhoto)
                    .authorName(uploader)
                    .text(caption.trim())
                    .isApproved(true)
                    .build();
            commentRepository.save(captionComment);
            savedPhoto.getComments().add(captionComment);
        }

        log.info("Foto persistida con ID {} para el evento ID {} (pendiente de aprobación)",
                savedPhoto.getId(), event.getId());

        return PhotoResponseDTO.fromEntity(savedPhoto, publicUrl, savedPhoto.getComments());
    }
}
