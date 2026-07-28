package com.tuapp.eventfoto.photo.dto;

public record UploadUrlResponseDTO(
    String uploadUrl,
    String key,
    String publicUrl
) {}
