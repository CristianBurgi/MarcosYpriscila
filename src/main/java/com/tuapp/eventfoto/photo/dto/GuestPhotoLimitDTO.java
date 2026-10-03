package com.tuapp.eventfoto.photo.dto;

/** Estado del límite de fotos por invitado de un evento, tal como lo muestra y lo guarda el panel. */
public record GuestPhotoLimitDTO(
    boolean unlimited,
    Integer maxPhotosPerGuest
) {}
