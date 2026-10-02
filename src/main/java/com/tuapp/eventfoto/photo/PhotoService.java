package com.tuapp.eventfoto.photo;

import com.tuapp.eventfoto.common.config.DeletionActor;
import com.tuapp.eventfoto.photo.dto.ConfirmUploadRequestDTO;
import com.tuapp.eventfoto.photo.dto.PhotoResponseDTO;
import com.tuapp.eventfoto.photo.dto.UploadUrlRequestDTO;
import com.tuapp.eventfoto.photo.dto.UploadUrlResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.web.multipart.MultipartFile;

import java.io.OutputStream;
import java.util.List;
import java.util.UUID;

public interface PhotoService {

    UploadUrlResponseDTO generateUploadUrl(String slug, UploadUrlRequestDTO request, String clientIp, String guestToken);

    PhotoResponseDTO confirmUpload(String slug, ConfirmUploadRequestDTO request);

    PhotoResponseDTO uploadDirect(String slug, MultipartFile file, String uploaderName, String caption, String guestToken);

    Page<PhotoResponseDTO> getPhotos(String slug, Pageable pageable);

    /** Borrado desde el panel del organizador. */
    void deletePhoto(UUID eventId, UUID photoId);

    /** Mismo borrado (storage -> base -> SSE), indicando quién lo hace solo para el log. */
    void deletePhoto(UUID eventId, UUID photoId, DeletionActor actor);

    long countTotalPhotos(String slug);

    String generateDownloadUrl(UUID eventId, UUID photoId);

    void streamPhotosZip(String slug, List<UUID> photoIds, OutputStream outputStream);
}
