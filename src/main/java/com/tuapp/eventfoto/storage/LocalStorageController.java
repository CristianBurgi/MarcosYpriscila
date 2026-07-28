package com.tuapp.eventfoto.storage;

import com.tuapp.eventfoto.common.exception.StorageException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@RestController
@RequestMapping("/api/v1/storage")
public class LocalStorageController {

    private static final String UPLOADS_DIR = "uploads";

    /**
     * PUT /api/v1/storage/local-upload?key=photos/...
     * Recibe los bytes de la foto en desarrollo local y los guarda en el disco local (/uploads).
     */
    @PutMapping("/local-upload")
    public ResponseEntity<Void> uploadLocalFile(
            @RequestParam String key,
            HttpServletRequest request) {

        try {
            Path targetPath = Paths.get(UPLOADS_DIR, key);
            Files.createDirectories(targetPath.getParent());

            byte[] bytes = request.getInputStream().readAllBytes();
            try (FileOutputStream fos = new FileOutputStream(targetPath.toFile())) {
                fos.write(bytes);
            }

            log.info("Archivo local guardado en desarrollo: {}", targetPath.toAbsolutePath());
            return ResponseEntity.ok().build();

        } catch (IOException e) {
            log.error("Error al guardar archivo local en desarrollo: {}", e.getMessage(), e);
            throw new StorageException("Error al guardar el archivo localmente", e);
        }
    }

    /**
     * GET /api/v1/storage/files?key=photos/...
     * Sirve los bytes de las fotos locales en desarrollo para que el navegador las muestre.
     */
    @GetMapping("/files")
    public ResponseEntity<Resource> getLocalFile(@RequestParam String key) {
        try {
            Path filePath = Paths.get(UPLOADS_DIR, key);
            File file = filePath.toFile();

            if (!file.exists()) {
                return ResponseEntity.notFound().build();
            }

            Resource resource = new UrlResource(filePath.toUri());
            String contentType = Files.probeContentType(filePath);
            if (contentType == null) {
                contentType = "image/jpeg";
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.getName() + "\"")
                    .body(resource);

        } catch (Exception e) {
            log.error("Error al servir archivo local: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }
}
