package com.tuapp.eventfoto.storage;

import com.tuapp.eventfoto.common.exception.StorageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Auditoría #5a: si R2 no firma la URL de subida, en producción (modo r2) no se puede devolver la URL de subida a disco
 * local: ese controlador no existe en producción y la foto terminaba en un 404. Tiene que fallar con StorageException
 * (500 para el invitado; el cliente reintenta y después prueba upload-direct).
 */
class R2UploadUrlFailureTest {

    private final S3Presigner presigner = mock(S3Presigner.class);

    private R2StorageService service(String mode) {
        R2StorageService service = new R2StorageService(mock(S3Client.class), presigner, mock(HeicConverter.class));
        ReflectionTestUtils.setField(service, "bucketName", "event-foto");
        ReflectionTestUtils.setField(service, "endpoint", "https://account-id.r2.cloudflarestorage.com");
        ReflectionTestUtils.setField(service, "storageMode", mode);
        return service;
    }

    @Test
    @DisplayName("Modo r2 y la firma falla: StorageException, nunca una URL de subida local")
    void r2FailureThrowsInsteadOfFallingBackToLocal() {
        when(presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                .thenThrow(SdkClientException.create("Unable to load credentials"));
        R2StorageService r2 = service("r2");

        assertThatThrownBy(() -> r2.generateUploadUrl("events/a/b.jpg", "image/jpeg"))
                .isInstanceOf(StorageException.class)
                .hasMessageNotContaining("local-upload")
                .hasMessageNotContaining("credentials");
    }

    @Test
    @DisplayName("Modo local: la URL de subida a disco sigue saliendo, sin pasar por R2")
    void localModeKeepsLocalUploadUrl() {
        String url = service("local").generateUploadUrl("events/a/b.jpg", "image/jpeg");

        assertThat(url).startsWith("/api/v1/storage/local-upload?key=");
        verifyNoInteractions(presigner);
    }
}
