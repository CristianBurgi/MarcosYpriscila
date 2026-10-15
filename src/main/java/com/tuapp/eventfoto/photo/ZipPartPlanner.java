package com.tuapp.eventfoto.photo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Parte el ZIP del álbum para que cada descarga termine antes del corte de 15 minutos de Railway. Sin leer los
 * archivos: por cantidad, app.zip.photos-per-part fotos por parte (500 de ~1,5 MB como peor caso, porque el celular
 * recomprime lo que pesa más). Cada parte va por orden de subida y se define por sus fotos de corte, no por posición:
 * borrar una foto entre la descarga de una parte y la siguiente no deja ninguna afuera (ver AlbumReader.loadPart).
 */
@Component
public class ZipPartPlanner {

    /**
     * Fotos {@code firstPhoto}..{@code lastPhoto} (base 1) de la parte {@code number} de {@code total}. {@code from} es
     * la primera foto de la parte; {@code to}, la primera de la siguiente (null en la última: llega hasta el final).
     */
    public record ZipPart(int number, int total, int firstPhoto, int lastPhoto, UUID from, UUID to) {
    }

    private final PhotoRepository photoRepository;
    private final int photosPerPart;

    public ZipPartPlanner(PhotoRepository photoRepository, @Value("${app.zip.photos-per-part:500}") int photosPerPart) {
        if (photosPerPart < 1) {
            throw new IllegalStateException("app.zip.photos-per-part tiene que ser 1 o más (es " + photosPerPart + ").");
        }
        this.photoRepository = photoRepository;
        this.photosPerPart = photosPerPart;
    }

    /** Vacío si el álbum entra en un solo ZIP: el panel muestra el botón único de siempre. */
    @Transactional(readOnly = true)
    public List<ZipPart> plan(UUID eventId) {
        return plan(photoRepository.findIdsInUploadOrder(eventId), photosPerPart);
    }

    static List<ZipPart> plan(List<UUID> idsInUploadOrder, int photosPerPart) {
        int count = idsInUploadOrder.size();
        if (count <= photosPerPart) {
            return List.of();
        }
        int total = (count + photosPerPart - 1) / photosPerPart;
        List<ZipPart> parts = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            int start = i * photosPerPart;
            int end = Math.min(start + photosPerPart, count);
            UUID to = end < count ? idsInUploadOrder.get(end) : null;
            parts.add(new ZipPart(i + 1, total, start + 1, end, idsInUploadOrder.get(start), to));
        }
        return parts;
    }
}
