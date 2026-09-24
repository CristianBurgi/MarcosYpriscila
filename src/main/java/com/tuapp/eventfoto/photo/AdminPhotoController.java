package com.tuapp.eventfoto.photo;

import com.tuapp.eventfoto.common.exception.InvalidFileFormatException;
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

@RestController
@RequestMapping("/api/v1/admin/photos")
@RequiredArgsConstructor
public class AdminPhotoController {

    private final PhotoService photoService;

    /**
     * GET /api/v1/admin/photos?slug=marcos-y-priscila
     * Devuelve la lista paginada de fotografías publicadas del evento.
     */
    @GetMapping
    public ResponseEntity<Page<PhotoResponseDTO>> getPhotos(
            @RequestParam(defaultValue = "marcos-y-priscila") String slug,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<PhotoResponseDTO> photos = photoService.getPhotos(slug, PageRequest.of(page, size));
        return ResponseEntity.ok(photos);
    }

    /**
     * DELETE /api/v1/admin/photos/{photoId}
     * Único control de moderación: elimina la fotografía de R2 y de la base de datos,
     * y notifica PHOTO_DELETED por SSE.
     */
    @DeleteMapping("/{photoId}")
    public ResponseEntity<Void> deletePhoto(@PathVariable String photoId) {
        UUID uuid = parseUUID(photoId);
        photoService.deletePhoto(uuid);
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /api/v1/admin/photos/{photoId}/download
     * Genera una Presigned GET URL en R2 y redirige (HTTP 302) al cliente para descarga directa.
     */
    @GetMapping("/{photoId}/download")
    public ResponseEntity<Void> downloadSinglePhoto(@PathVariable String photoId) {
        UUID uuid = parseUUID(photoId);
        String presignedDownloadUrl = photoService.generateDownloadUrl(uuid);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(presignedDownloadUrl))
                .build();
    }

    /**
     * GET /api/v1/admin/photos/download-zip?slug=marcos-y-priscila&photoIds=uuid1,uuid2
     * Transmite en tiempo real (streaming) un archivo ZIP con las fotografías del evento.
     */
    @GetMapping({"/download-zip", "/events/{slug}/download-zip"})
    public void downloadPhotosZip(
            // Antes el parámetro se llamaba slugPath y nunca se enlazaba con {slug}: la ruta
            // /events/{slug}/download-zip siempre descargaba el evento por defecto.
            @PathVariable(name = "slug", required = false) String slugPath,
            @RequestParam(name = "slug", defaultValue = "marcos-y-priscila") String slugParam,
            @RequestParam(required = false) List<String> photoIds,
            HttpServletResponse response) throws IOException {

        String effectiveSlug = (slugPath != null && !slugPath.isBlank()) ? slugPath : slugParam;

        List<UUID> parsedUuids = Collections.emptyList();
        if (photoIds != null && !photoIds.isEmpty()) {
            parsedUuids = photoIds.stream()
                    .map(this::parseUUID)
                    .toList();
        }

        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", String.format("attachment; filename=\"album-%s.zip\"", effectiveSlug));

        photoService.streamPhotosZip(effectiveSlug, parsedUuids, response.getOutputStream());
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
