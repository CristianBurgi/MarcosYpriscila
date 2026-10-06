package com.tuapp.eventfoto.event.dto;

import java.time.LocalDate;

/**
 * Fecha y personalización de un evento, como las ven el wizard y la tarjeta "Personalización".
 * {@code backgroundColor} null = paleta por defecto; {@code dateEditable} false = ya abrió la subida de fotos.
 */
public record EventSettingsDTO(LocalDate eventDate, String backgroundColor, String backgroundImageUrl,
                               boolean dateEditable, boolean wizardCompleted) {

    public record DateRequest(String date) {
    }

    public record ColorRequest(String color) {
    }
}
