package com.tuapp.eventfoto.photo;

import com.tuapp.eventfoto.photo.dto.ConfirmUploadRequestDTO;
import com.tuapp.eventfoto.photo.dto.PhotoResponseDTO;
import com.tuapp.eventfoto.photo.dto.UploadUrlRequestDTO;
import com.tuapp.eventfoto.photo.dto.UploadUrlResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface PhotoService {

    UploadUrlResponseDTO generateUploadUrl(String slug, UploadUrlRequestDTO request, String clientIp);

    PhotoResponseDTO confirmUpload(String slug, ConfirmUploadRequestDTO request);

    PhotoResponseDTO uploadDirect(String slug, MultipartFile file, String uploaderName, String caption);

    Page<PhotoResponseDTO> getApprovedPhotos(String slug, Pageable pageable);

    Page<PhotoResponseDTO> getPendingPhotos(String slug, Pageable pageable);

    PhotoResponseDTO approvePhoto(UUID photoId);

    void rejectPhoto(UUID photoId);

    void deletePhoto(UUID photoId);

    List<PhotoResponseDTO> approveAllPendingPhotos(String slug);

    long countTotalPhotos(String slug);

    long countPendingPhotos(String slug);
}
