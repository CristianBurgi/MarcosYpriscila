package com.tuapp.eventfoto.photo.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateGuestPhotoLimitRequestDTO(
    @NotNull(message = "Indicá si querés el límite o que sea sin límite")
    Boolean unlimited
) {}
