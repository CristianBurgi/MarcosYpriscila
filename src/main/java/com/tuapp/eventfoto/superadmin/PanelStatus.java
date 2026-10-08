package com.tuapp.eventfoto.superadmin;

import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.UploadWindow;

/** Estado del evento en el listado del superadmin. Se calcula al servir la página, nunca se guarda. */
public enum PanelStatus {
    NOT_CONFIGURED("sin configurar"),
    WAITING("esperando la fecha"),
    OPEN("subida abierta"),
    CLOSED_BY_ORGANIZER("cerrado por el organizador"),
    CLOSED("subida cerrada"),
    EXPIRED("vencido"),
    PURGED("borrado");

    public final String label;

    PanelStatus(String label) {
        this.label = label;
    }

    /** Lo más definitivo gana: borrado, sin configurar, vencido, y recién después la ventana de subida. */
    public static PanelStatus of(Event event, UploadWindow window) {
        if (event.getPurgedAt() != null) {
            return PURGED;
        }
        if (event.getWizardCompletedAt() == null || event.getEventDate() == null) {
            return NOT_CONFIGURED;
        }
        if (window.isExpired(event)) {
            return EXPIRED;
        }
        return switch (window.status(event)) {
            case OPEN -> OPEN;
            case NOT_OPEN -> WAITING;
            case CLOSED -> CLOSED;
            case CLOSED_BY_ORGANIZER -> CLOSED_BY_ORGANIZER;
            case NOT_READY -> NOT_CONFIGURED;
        };
    }
}
