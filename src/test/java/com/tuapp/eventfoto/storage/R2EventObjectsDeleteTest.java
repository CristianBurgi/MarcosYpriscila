package com.tuapp.eventfoto.storage;

import com.tuapp.eventfoto.common.exception.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Fase 9.6: borrado de events/{id}/ en R2 con ListObjectsV2 + DeleteObjects por lotes de hasta 1.000. */
class R2EventObjectsDeleteTest {

    private final UUID eventId = UUID.randomUUID();
    private final S3Client s3Client = mock(S3Client.class);
    private R2StorageService storage;

    @BeforeEach
    void setUp() {
        storage = new R2StorageService(s3Client, mock(S3Presigner.class), mock(HeicConverter.class));
        ReflectionTestUtils.setField(storage, "bucketName", "bucket");
        ReflectionTestUtils.setField(storage, "endpoint", "https://account-id.r2.cloudflarestorage.com");
        ReflectionTestUtils.setField(storage, "storageMode", "r2");
    }

    private ListObjectsV2Response page(int objects) {
        return ListObjectsV2Response.builder().contents(IntStream.range(0, objects)
                .mapToObj(i -> S3Object.builder().key("events/" + eventId + "/" + i + ".jpg").build()).toList()).build();
    }

    @Test
    @DisplayName("Relista y borra por lotes hasta que el prefijo queda vacío; solo lista bajo events/{id}/")
    void deletesInBatchesUntilEmpty() {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page(1000), page(3), page(0));
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DeleteObjectsResponse.builder().build());

        assertThat(storage.deleteEventObjects(eventId)).isEqualTo(1003);

        ArgumentCaptor<ListObjectsV2Request> list = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(s3Client, times(3)).listObjectsV2(list.capture());
        assertThat(list.getAllValues()).allSatisfy(request -> {
            assertThat(request.prefix()).isEqualTo("events/" + eventId + "/");
            assertThat(request.maxKeys()).isEqualTo(1000);
        });
        verify(s3Client, times(2)).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    @DisplayName("Sin objetos (segunda corrida): 0 y ningún DeleteObjects")
    void nothingToDelete() {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page(0));
        assertThat(storage.deleteEventObjects(eventId)).isZero();
        verify(s3Client, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    @DisplayName("Un objeto que R2 no borró es un error: quien llama no toca la base")
    void partialDeleteFails() {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page(2));
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DeleteObjectsResponse.builder()
                .errors(S3Error.builder().key("events/" + eventId + "/0.jpg").code("InternalError").build()).build());
        assertThatThrownBy(() -> storage.deleteEventObjects(eventId)).isInstanceOf(StorageException.class).hasMessageContaining("1 de 2");
    }

    @Test
    @DisplayName("Un listado que nunca se vacía corta en 100 vueltas con excepción (no cuelga el job)")
    void listingThatNeverEmptiesHitsTheCap() {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page(5));
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DeleteObjectsResponse.builder().build());

        assertThatThrownBy(() -> storage.deleteEventObjects(eventId)).isInstanceOf(StorageException.class).hasMessageContaining("100");
        verify(s3Client, times(R2StorageService.MAX_DELETE_PAGES)).deleteObjects(any(DeleteObjectsRequest.class));
        assertThat(List.of(R2StorageService.MAX_DELETE_PAGES, R2StorageService.DELETE_BATCH_SIZE)).containsExactly(100, 1000);
    }
}
