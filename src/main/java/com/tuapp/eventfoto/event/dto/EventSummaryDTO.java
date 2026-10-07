package com.tuapp.eventfoto.event.dto;

import java.time.LocalDate;

/**
 * Fila de la pantalla "Mis eventos". {@code configured} = el wizard ya está completo; {@code expired} = el álbum venció
 * (UploadWindow#isExpired, lo completa EventAccessService: la consulta lo trae en false).
 */
public record EventSummaryDTO(String name, String slug, LocalDate eventDate, boolean isActive, boolean configured, long photoCount,
                              boolean expired) {

    public EventSummaryDTO withExpired(boolean expired) {
        return new EventSummaryDTO(name, slug, eventDate, isActive, configured, photoCount, expired);
    }
}
