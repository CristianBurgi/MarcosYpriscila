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
     * GET /api/v1/admin/photos/pending?slug=marcos-y-priscila
     * Devuelve la lista paginada de fotografías pendientes de moderación.
     */
    @GetMapping("/pending")
    public ResponseEntity<Page<PhotoResponseDTO>> getPendingPhotos(
            @RequestParam(defaultValue = "marcos-y-priscila") String slug,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<PhotoResponseDTO> pending = photoService.getPendingPhotos(slug, PageRequest.of(page, size));
        return ResponseEntity.ok(pending);
    }

    /**
     * GET /api/v1/admin/photos/approved?slug=marcos-y-priscila
     * Devuelve la lista paginada de fotografías aprobadas y visibles en el álbum.
     */
    @GetMapping("/approved")
    public ResponseEntity<Page<PhotoResponseDTO>> getApprovedPhotos(
            @RequestParam(defaultValue = "marcos-y-priscila") String slug,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<PhotoResponseDTO> approved = photoService.getApprovedPhotos(slug, PageRequest.of(page, size));
        return ResponseEntity.ok(approved);
    }

    /**
     * PATCH /api/v1/admin/photos/{photoId}/approve
     * Aprueba una fotografía individual para que aparezca en el álbum público y dispara el evento SSE.
     */
    @PatchMapping("/{photoId}/approve")
    public ResponseEntity<PhotoResponseDTO> approvePhoto(@PathVariable String photoId) {
        UUID uuid = parseUUID(photoId);
        PhotoResponseDTO approvedPhoto = photoService.approvePhoto(uuid);
        return ResponseEntity.ok(approvedPhoto);
    }

    /**
     * DELETE /api/v1/admin/photos/{photoId}
     * PATCH /api/v1/admin/photos/{photoId}/reject
     * Rechaza y elimina una fotografía de R2 y de la base de datos.
     */
    @DeleteMapping("/{photoId}")
    public ResponseEntity<Void> rejectPhoto(@PathVariable String photoId) {
        UUID uuid = parseUUID(photoId);
        photoService.rejectPhoto(uuid);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{photoId}/reject")
    public ResponseEntity<Void> rejectPhotoPatch(@PathVariable String photoId) {
        return rejectPhoto(photoId);
    }

    /**
     * POST /api/v1/admin/photos/approve-all?slug=marcos-y-priscila
     * Aprueba masivamente todas las fotografías pendientes del evento.
     */
    @PostMapping("/approve-all")
    public ResponseEntity<List<PhotoResponseDTO>> approveAllPhotos(
            @RequestParam(defaultValue = "marcos-y-priscila") String slug) {
        List<PhotoResponseDTO> approvedPhotos = photoService.approveAllPendingPhotos(slug);
        return ResponseEntity.ok(approvedPhotos);
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
     * Transmite en tiempo real (streaming) un archivo ZIP con las fotografías aprobadas.
     */
    @GetMapping({"/download-zip", "/events/{slug}/download-zip"})
    public void downloadPhotosZip(
            @PathVariable(required = false) String slugPath,
            @RequestParam(defaultValue = "marcos-y-priscila") String slug,
            @RequestParam(required = false) List<String> photoIds,
            HttpServletResponse response) throws IOException {

        String effectiveSlug = (slugPath != null && !slugPath.isBlank()) ? slugPath : slug;

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
