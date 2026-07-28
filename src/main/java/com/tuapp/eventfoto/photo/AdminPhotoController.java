package com.tuapp.eventfoto.photo;

import com.tuapp.eventfoto.common.exception.InvalidFileFormatException;
import com.tuapp.eventfoto.photo.dto.PhotoResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
     * Rechaza y elimina una fotografía.
     */
    @DeleteMapping("/{photoId}")
    public ResponseEntity<Void> rejectPhoto(@PathVariable String photoId) {
        UUID uuid = parseUUID(photoId);
        photoService.rejectPhoto(uuid);
        return ResponseEntity.noContent().build();
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

    private UUID parseUUID(String rawId) {
        try {
            String cleanId = rawId.trim().replaceAll("[\\r\\n\\t]", "");
            return UUID.fromString(cleanId);
        } catch (IllegalArgumentException e) {
            throw new InvalidFileFormatException("El ID de fotografía provisto no tiene un formato UUID válido: '" + rawId + "'");
        }
    }
}
