package com.tuapp.eventfoto.storage;

import com.tuapp.eventfoto.common.exception.InvalidFileContentException;
import com.tuapp.eventfoto.common.exception.StorageException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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

    @Value("${app.storage.mode:local}")
    private String storageMode;

    /**
     * PUT /api/v1/storage/local-upload?key=photos/...
     * Recibe los bytes de la foto en desarrollo local y los guarda en el disco local (/uploads).
     * En producción (mode=r2) responde inmediatamente 403 Forbidden.
     */
    @PutMapping("/local-upload")
    public ResponseEntity<Void> uploadLocalFile(
            @RequestParam String key,
            HttpServletRequest request) {

        if (!"local".equalsIgnoreCase(storageMode)) {
            log.warn("Intento de subida local bloqueado porque app.storage.mode es '{}'", storageMode);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            Path baseDir = Paths.get(UPLOADS_DIR).toAbsolutePath().normalize();
            Path targetPath = baseDir.resolve(key).normalize();

            if (!targetPath.startsWith(baseDir)) {
                log.error("Acceso denegado: intento de path traversal detectado en key='{}'", key);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            Files.createDirectories(targetPath.getParent());

            byte[] bytes = request.getInputStream().readAllBytes();

            // Verificación de contenido real (magic bytes) antes de escribir a disco,
            // igual que en el flujo de R2: no confiar únicamente en el Content-Type declarado.
            byte[] header = bytes.length > 12 ? java.util.Arrays.copyOf(bytes, 12) : bytes;
            if (!FileSignatureValidator.isValidImageSignature(header)) {
                log.warn("Subida local rechazada: la firma binaria del archivo no corresponde a una imagen válida para key='{}'", key);
                throw new InvalidFileContentException(
                        "El archivo subido no corresponde a una imagen válida (JPEG, PNG, WEBP o HEIC). La subida fue rechazada."
                );
            }

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
            Path baseDir = Paths.get(UPLOADS_DIR).toAbsolutePath().normalize();
            Path filePath = baseDir.resolve(key).normalize();

            if (!filePath.startsWith(baseDir)) {
                log.error("Acceso denegado: intento de path traversal detectado en key='{}'", key);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

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

