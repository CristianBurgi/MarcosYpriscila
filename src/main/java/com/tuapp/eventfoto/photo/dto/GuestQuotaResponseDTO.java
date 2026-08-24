package com.tuapp.eventfoto.photo.dto;

public record GuestQuotaResponseDTO(
    int remainingPhotos,
    int maxPhotosPerGuest
) {}
