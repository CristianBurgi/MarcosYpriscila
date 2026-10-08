package com.tuapp.eventfoto.photo;

import com.tuapp.eventfoto.common.exception.InvalidFileFormatException;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.OwnedEvent;
import com.tuapp.eventfoto.photo.dto.PhotoResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Fotos del panel. Todas las rutas cuelgan de /events/{slug}: el evento sale de la ruta,
 * se valida que sea del organizador logueado (@OwnedEvent) y cada foto se busca acotada
 * a ese evento.
 */
@RestController
@RequestMapping("/api/v1/admin/events/{slug}/photos")
@RequiredArgsConstructor
public class AdminPhotoController {

    private final PhotoService photoService;

    /**
     * GET /api/v1/admin/events/{slug}/photos
     * Devuelve la lista paginada de fotografías publicadas del evento.
     */
    @GetMapping
    public ResponseEntity<Page<PhotoResponseDTO>> getPhotos(
            @OwnedEvent Event event,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<PhotoResponseDTO> photos = photoService.getPhotos(event.getSlug(), PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
        return ResponseEntity.ok(photos);
    }

    /**
     * DELETE /api/v1/admin/events/{slug}/photos/{photoId}
     * Único control de moderación: elimina la fotografía de R2 y de la base de datos,
     * y notifica PHOTO_DELETED por SSE.
     */
    @DeleteMapping("/{photoId}")
    public ResponseEntity<Void> deletePhoto(@OwnedEvent Event event, @PathVariable String photoId) {
        photoService.deletePhoto(event.getId(), parseUUID(photoId));
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /api/v1/admin/events/{slug}/photos/{photoId}/download
     * Genera una Presigned GET URL en R2 y redirige (HTTP 302) al cliente para descarga directa.
     */
    @GetMapping("/{photoId}/download")
    public ResponseEntity<Void> downloadSinglePhoto(@OwnedEvent Event event, @PathVariable String photoId) {
        String presignedDownloadUrl = photoService.generateDownloadUrl(event.getId(), parseUUID(photoId));
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(presignedDownloadUrl))
                .build();
    }

    /**
     * GET /api/v1/admin/events/{slug}/photos/download-zip?photoIds=uuid1,uuid2
     * Transmite en tiempo real (streaming) un archivo ZIP con las fotografías del evento.
     * Los photoIds que no son de este evento se ignoran (la consulta está acotada al evento).
     */
    @GetMapping("/download-zip")
    public void downloadPhotosZip(
            @OwnedEvent Event event,
            @RequestParam(required = false) List<String> photoIds,
            HttpServletResponse response) throws IOException {

        List<UUID> parsedUuids = Collections.emptyList();
        if (photoIds != null && !photoIds.isEmpty()) {
            parsedUuids = photoIds.stream()
                    .map(this::parseUUID)
                    .toList();
        }

        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", String.format("attachment; filename=\"album-%s.zip\"", event.getSlug()));

        photoService.streamPhotosZip(event.getSlug(), parsedUuids, response.getOutputStream());
    }

    private UUID parseUUID(String rawId) {
        try {
            String cleanId = rawId.trim().replaceAll("[\\r\\n\\t]", "");
            return UUID.fromString(cleanId);
        } catch (IllegalArgumentException e) {
            throw new InvalidFileFormatException("El ID de fotografía provisto no tiene un formato UUID válido: '" + rawId + "'");
        }
    }
}
