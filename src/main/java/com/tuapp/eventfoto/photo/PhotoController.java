package com.tuapp.eventfoto.photo;

import com.tuapp.eventfoto.photo.dto.ConfirmUploadRequestDTO;
import com.tuapp.eventfoto.photo.dto.PhotoResponseDTO;
import com.tuapp.eventfoto.photo.dto.UploadUrlRequestDTO;
import com.tuapp.eventfoto.photo.dto.UploadUrlResponseDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/events/{slug}/photos")
@RequiredArgsConstructor
public class PhotoController {

    private final PhotoService photoService;

    /**
     * POST /api/v1/events/{slug}/photos/upload-url
     * Genera la presigned URL para que el navegador del invitado suba la foto directo a Cloudflare R2.
     */
    @PostMapping("/upload-url")
    public ResponseEntity<UploadUrlResponseDTO> generateUploadUrl(
            @PathVariable String slug,
            @Valid @RequestBody UploadUrlRequestDTO request,
            HttpServletRequest servletRequest) {

        String clientIp = getClientIp(servletRequest);
        UploadUrlResponseDTO response = photoService.generateUploadUrl(slug, request, clientIp);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/v1/events/{slug}/photos/confirm
     * Confirma que la subida a R2 finalizó y registra la foto en la base de datos (isApproved=false por defecto).
     */
    @PostMapping("/confirm")
    public ResponseEntity<PhotoResponseDTO> confirmUpload(
            @PathVariable String slug,
            @Valid @RequestBody ConfirmUploadRequestDTO request) {

        PhotoResponseDTO response = photoService.confirmUpload(slug, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * POST /api/v1/events/{slug}/photos/upload-direct
     * Subida directa multipart a través del servidor para casos donde el cliente móvil no pueda hacer PUT directo a R2.
     */
    @PostMapping(value = "/upload-direct", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PhotoResponseDTO> uploadDirect(
            @PathVariable String slug,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam("uploaderName") String uploaderName,
            @RequestParam(value = "caption", required = false) String caption) {

        PhotoResponseDTO response = photoService.uploadDirect(slug, file, uploaderName, caption);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /api/v1/events/{slug}/photos?page=0&size=20
     * Devuelve las fotos aprobadas del evento para la galería pública (paginado).
     */
    @GetMapping
    public ResponseEntity<Page<PhotoResponseDTO>> getApprovedPhotos(
            @PathVariable String slug,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<PhotoResponseDTO> photos = photoService.getApprovedPhotos(slug, pageable);
        return ResponseEntity.ok(photos);
    }

    private String getClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null || xfHeader.isBlank()) {
            return request.getRemoteAddr();
        }
        return xfHeader.split(",")[0].trim();
    }
}
