package com.tuapp.eventfoto.event.dto;

import java.time.LocalDate;

/** Fila de la pantalla "Mis eventos". {@code configured} = el wizard ya está completo. */
public record EventSummaryDTO(String name, String slug, LocalDate eventDate, boolean isActive, boolean configured, long photoCount) {
}
