package com.tuapp.eventfoto.storage;

import com.tuapp.eventfoto.common.exception.InvalidFileFormatException;
import com.tuapp.eventfoto.common.exception.StorageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class R2StorageService implements StorageService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/webp",
            "image/heic",
            "image/heif"
    );

    private static final Duration PRESIGNED_URL_DURATION = Duration.ofMinutes(10);

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final HeicConverter heicConverter;

    @Value("${cloudflare.r2.bucket-name}")
    private String bucketName;

    @Value("${cloudflare.r2.endpoint}")
    private String endpoint;

    @Value("${cloudflare.r2.public-url:}")
    private String publicUrlDomain;

    @Value("${app.storage.mode:local}")
    private String storageMode;

    @Override
    public String generateUploadUrl(String key, String contentType) {
        validateContentType(contentType);

        // Si estamos en entorno de desarrollo local sin R2 configurado reales, usamos el controlador local
        if (isLocalDevMode()) {
            log.info("Modo desarrollo local activo: Generando URL de subida local para la clave '{}'", key);
            return "/api/v1/storage/local-upload?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
        }

        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                    .signatureDuration(PRESIGNED_URL_DURATION)
                    .putObjectRequest(putObjectRequest)
                    .build();

            PresignedPutObjectRequest presignedRequest = s3Presigner.presignPutObject(presignRequest);
            String presignedUrl = presignedRequest.url().toString();
            log.info("Presigned URL de subida generada exitosamente para clave '{}' en R2", key);
            return presignedUrl;

        } catch (Exception e) {
            log.error("Error al generar Presigned URL en R2. Usando fallback local para desarrollo: {}", e.getMessage());
            return "/api/v1/storage/local-upload?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
        }
    }

    @Override
    public String generatePublicUrl(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }

        if (isLocalDevMode()) {
            String cleanKey = key.startsWith("/") ? key.substring(1) : key;
            return "/uploads/" + cleanKey;
        }

        String cleanKey = key.startsWith("/") ? key.substring(1) : key;

        if (publicUrlDomain != null && !publicUrlDomain.isBlank()) {
            String base = publicUrlDomain.endsWith("/") ? publicUrlDomain.substring(0, publicUrlDomain.length() - 1) : publicUrlDomain;
            return String.format("%s/%s", base, cleanKey);
        }

        String baseEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        if (bucketName != null && !bucketName.isBlank() && baseEndpoint.endsWith("/" + bucketName)) {
            baseEndpoint = baseEndpoint.substring(0, baseEndpoint.length() - (bucketName.length() + 1));
        }

        return String.format("%s/%s/%s", baseEndpoint, bucketName, cleanKey);
    }

    @Override
    public void uploadBytes(String key, byte[] bytes, String contentType) {
        validateContentType(contentType);

        if (isLocalDevMode()) {
            log.info("Modo local: Guardando bytes de imagen convertida HEIC localmente");
            return;
        }

        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(contentType)
                    .contentLength((long) bytes.length)
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(bytes));
            log.info("Archivo de {} bytes subido exitosamente a Cloudflare R2 con la clave '{}'", bytes.length, key);

        } catch (Exception e) {
            log.error("Error al subir los bytes del archivo a Cloudflare R2: {}", e.getMessage(), e);
            throw new StorageException("Error al subir el archivo procesado a Cloudflare R2", e);
        }
    }

    @Override
    public byte[] convertHeicToJpeg(byte[] heicBytes) {
        return heicConverter.convertToJpeg(heicBytes);
    }

    @Override
    public void deleteFile(String key) {
        if (key == null || key.isBlank()) {
            return;
        }

        if (isLocalDevMode()) {
            log.info("Modo local: Eliminando archivo '{}' del almacenamiento local", key);
            String cleanKey = key.startsWith("/") ? key.substring(1) : key;
            Path localPath = Paths.get("uploads", cleanKey);
            try {
                Files.deleteIfExists(localPath);
            } catch (IOException e) {
                log.warn("No se pudo eliminar el archivo local '{}': {}", localPath, e.getMessage());
            }
            return;
        }

        try {
            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            s3Client.deleteObject(deleteObjectRequest);
            log.info("Archivo con clave '{}' eliminado exitosamente de Cloudflare R2", key);
        } catch (Exception e) {
            log.error("Error al eliminar el archivo con clave '{}' de Cloudflare R2: {}", key, e.getMessage(), e);
            throw new StorageException("Error al eliminar el archivo de Cloudflare R2", e);
        }
    }

    @Override
    public String generateDownloadUrl(String key) {
        if (key == null || key.isBlank()) {
            throw new StorageException("No se especificó la clave del archivo para generar URL de descarga.");
        }

        if (isLocalDevMode()) {
            log.info("Modo local activo: Retornando URL pública local para descarga de la clave '{}'", key);
            return generatePublicUrl(key);
        }

        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(PRESIGNED_URL_DURATION)
                    .getObjectRequest(getObjectRequest)
                    .build();

            PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
            String downloadUrl = presignedRequest.url().toString();
            log.info("Presigned URL de descarga generada exitosamente para clave '{}' en R2", key);
            return downloadUrl;

        } catch (Exception e) {
            log.error("Error al generar Presigned URL de descarga en R2: {}", e.getMessage(), e);
            return generatePublicUrl(key);
        }
    }

    @Override
    public InputStream streamObject(String key) {
        if (key == null || key.isBlank()) {
            throw new StorageException("No se especificó la clave del archivo para transmisión.");
        }

        if (isLocalDevMode()) {
            log.info("Modo local: Abriendo InputStream local para la clave '{}'", key);
            String cleanKey = key.startsWith("/") ? key.substring(1) : key;
            Path localPath = Paths.get("uploads", cleanKey);
            try {
                if (Files.exists(localPath)) {
                    return Files.newInputStream(localPath);
                } else {
                    // Generar un stream vacío o simulado si el archivo local de pruebas no existe en disco
                    log.warn("Archivo local '{}' no existe en disco, retornando stream en blanco", localPath);
                    return InputStream.nullInputStream();
                }
            } catch (IOException e) {
                log.error("Error al abrir InputStream local para {}: {}", localPath, e.getMessage());
                throw new StorageException("Error al leer el archivo local: " + localPath, e);
            }
        }

        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            return s3Client.getObject(getObjectRequest);
        } catch (Exception e) {
            log.error("Error al obtener InputStream del objeto '{}' en Cloudflare R2: {}", key, e.getMessage(), e);
            throw new StorageException("Error al transmitir el archivo desde Cloudflare R2", e);
        }
    }

    private boolean isLocalDevMode() {
        return "local".equalsIgnoreCase(storageMode)
                || endpoint == null
                || endpoint.contains("localhost")
                || endpoint.contains("dummy")
                || endpoint.contains("placeholder");
    }

    private void validateContentType(String contentType) {
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase().trim())) {
            log.warn("Intento de presigned URL con tipo de contenido no permitido: '{}'", contentType);
            throw new InvalidFileFormatException(
                    String.format("El formato de archivo '%s' no está permitido. Solo se aceptan imágenes JPEG, PNG, WEBP y HEIC.", contentType)
            );
        }
    }
}
