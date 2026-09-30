package com.tuapp.eventfoto.event.dto;

import java.time.LocalDate;

/** Fila de la pantalla "Mis eventos". */
public record EventSummaryDTO(String name, String slug, LocalDate eventDate, boolean isActive, long photoCount) {
}
