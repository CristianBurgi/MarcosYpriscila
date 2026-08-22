package com.tuapp.eventfoto.storage;

import com.tuapp.eventfoto.common.exception.InvalidFileFormatException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StorageServiceTest {

    @Mock
    private S3Client s3Client;

    @Mock
    private S3Presigner s3Presigner;

    @Mock
    private HeicConverter heicConverter;

    @Mock
    private PresignedPutObjectRequest presignedPutObjectRequest;

    private R2StorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new R2StorageService(s3Client, s3Presigner, heicConverter);
        ReflectionTestUtils.setField(storageService, "bucketName", "test-boda-bucket");
        ReflectionTestUtils.setField(storageService, "endpoint", "https://account-id.r2.cloudflarestorage.com");
    }

    @Test
    @DisplayName("Debe generar exitosamente una Presigned URL para un tipo de contenido válido (image/jpeg)")
    void shouldGenerateUploadUrlForValidContentType() throws MalformedURLException {
        // Arrange
        String key = "photos/boda_123.jpg";
        String contentType = "image/jpeg";
        URL fakeUrl = URI.create("https://account-id.r2.cloudflarestorage.com/test-boda-bucket/" + key + "?signature=abc12345").toURL();

        when(presignedPutObjectRequest.url()).thenReturn(fakeUrl);
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presignedPutObjectRequest);

        // Act
        String presignedUrl = storageService.generateUploadUrl(key, contentType);

        // Assert
        assertNotNull(presignedUrl);
        assertTrue(presignedUrl.contains("signature=abc12345"));
        verify(s3Presigner, times(1)).presignPutObject(any(PutObjectPresignRequest.class));
    }

    @Test
    @DisplayName("Debe rechazar y lanzar InvalidFileFormatException si el content type es inválido (application/pdf)")
    void shouldThrowExceptionForInvalidContentType() {
        // Arrange
        String key = "documents/archivo.pdf";
        String contentType = "application/pdf";

        // Act & Assert
        InvalidFileFormatException exception = assertThrows(
                InvalidFileFormatException.class,
                () -> storageService.generateUploadUrl(key, contentType)
        );

        assertTrue(exception.getMessage().contains("application/pdf"));
        verifyNoInteractions(s3Presigner);
    }

    @Test
    @DisplayName("Debe permitir formatos image/png, image/webp e image/heic")
    void shouldAllowAllSupportedImageFormats() throws MalformedURLException {
        // Arrange
        URL fakeUrl = URI.create("https://account-id.r2.cloudflarestorage.com/test-boda-bucket/test.img").toURL();
        when(presignedPutObjectRequest.url()).thenReturn(fakeUrl);
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presignedPutObjectRequest);

        // Act & Assert
        assertDoesNotThrow(() -> storageService.generateUploadUrl("test.png", "image/png"));
        assertDoesNotThrow(() -> storageService.generateUploadUrl("test.webp", "image/webp"));
        assertDoesNotThrow(() -> storageService.generateUploadUrl("test.heic", "image/heic"));
    }

    @Test
    @DisplayName("Debe generar correctamente la URL pública de lectura de un objeto")
    void shouldGeneratePublicUrl() {
        // Arrange
        String key = "photos/foto1.jpg";

        // Act
        String publicUrl = storageService.generatePublicUrl(key);

        // Assert
        assertEquals("https://account-id.r2.cloudflarestorage.com/test-boda-bucket/photos/foto1.jpg", publicUrl);
    }

    @Test
    @DisplayName("Debe invocar a S3Client.deleteObject al llamar a deleteFile")
    void shouldDeleteFileFromR2() {
        // Arrange
        String key = "photos/foto1.jpg";

        // Act
        assertDoesNotThrow(() -> storageService.deleteFile(key));

        // Assert
        verify(s3Client, times(1)).deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("Debe generar exitosamente una Presigned GET URL de descarga")
    void shouldGenerateDownloadUrl() throws MalformedURLException {
        // Arrange
        String key = "photos/foto1.jpg";
        URL fakeUrl = URI.create("https://account-id.r2.cloudflarestorage.com/test-boda-bucket/" + key + "?signature=download123").toURL();
        software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest presignedGetObjectRequest = mock(software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest.class);

        when(presignedGetObjectRequest.url()).thenReturn(fakeUrl);
        when(s3Presigner.presignGetObject(any(software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest.class))).thenReturn(presignedGetObjectRequest);

        // Act
        String downloadUrl = storageService.generateDownloadUrl(key);

        // Assert
        assertNotNull(downloadUrl);
        assertTrue(downloadUrl.contains("signature=download123"));
        verify(s3Presigner, times(1)).presignGetObject(any(software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest.class));
    }
}
