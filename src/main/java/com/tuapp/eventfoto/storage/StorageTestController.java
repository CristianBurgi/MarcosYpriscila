package com.tuapp.eventfoto.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/storage/test")
@RequiredArgsConstructor
@Profile({"dev", "local", "test"})
public class StorageTestController {


    private final StorageService storageService;

    /**
     * Endpoint de prueba para solicitar una Presigned URL de subida directa a Cloudflare R2.
     * Ejemplo de consulta GET:
     *   /api/storage/test/presigned-url?contentType=image/jpeg
     *   /api/storage/test/presigned-url?contentType=application/pdf (Debe devolver HTTP 400 Bad Request)
     */
    @GetMapping("/presigned-url")
    public ResponseEntity<Map<String, String>> getTestPresignedUrl(
            @RequestParam(defaultValue = "image/jpeg") String contentType) {

        String key = "test-uploads/" + UUID.randomUUID() + getExtensionForContentType(contentType);
        String presignedUrl = storageService.generateUploadUrl(key, contentType);
        String publicUrl = storageService.generatePublicUrl(key);

        return ResponseEntity.ok(Map.of(
                "key", key,
                "contentType", contentType,
                "presignedUrl", presignedUrl,
                "publicUrl", publicUrl
        ));
    }

    private String getExtensionForContentType(String contentType) {
        if (contentType == null) return ".jpg";
        return switch (contentType.toLowerCase().trim()) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/heic" -> ".heic";
            default -> ".jpg";
        };
    }
}
