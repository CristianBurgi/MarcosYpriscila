package com.tuapp.eventfoto.photo;

import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Lectura corta del álbum para armar el ZIP. Vive en un bean aparte para que la
 * transacción se abra y se CIERRE acá, antes de generar el PDF y de transmitir las fotos
 * desde R2: una autoinvocación dentro de PhotoServiceImpl no pasaría por el proxy de
 * Spring y la transacción no se cerraría a tiempo.
 *
 * Devuelve datos simples (no entidades) para que después de la transacción no haya nada
 * que dispare un acceso lazy.
 */
@Component
@RequiredArgsConstructor
public class AlbumReader {

    /** Lo único que el ZIP necesita saber de una foto. */
    public record ZipPhoto(UUID id, String storageKey, String uploaderName) {
    }

    private final PhotoRepository photoRepository;
    private final EventService eventService;

    @Transactional(readOnly = true)
    public List<ZipPhoto> loadPhotos(String slug, List<UUID> photoIds) {
        Event event = eventService.getEventEntityBySlug(slug);
        List<Photo> photos = (photoIds != null && !photoIds.isEmpty())
                ? photoRepository.findByEventIdAndIdIn(event.getId(), photoIds)
                : photoRepository.findByEventId(event.getId());
        return photos.stream()
                .map(photo -> new ZipPhoto(photo.getId(), photo.getStorageKey(), photo.getUploaderName()))
                .toList();
    }
}
