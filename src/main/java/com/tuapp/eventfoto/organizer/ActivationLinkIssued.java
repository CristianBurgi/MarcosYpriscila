package com.tuapp.eventfoto.organizer;

import java.util.UUID;

/**
 * Se emitió un link de activación (evento sin costo para una cuenta nueva, o link regenerado). Se publica dentro de la
 * transacción y el mail sale recién después del commit (OrganizerAccountMailer): si la transacción se revierte, el link
 * no existe y no se manda nada. {@code eventName} es null cuando el link se regeneró.
 */
public record ActivationLinkIssued(UUID organizerId, String email, String activationUrl, String eventName) {

    @Override
    public String toString() {
        // El link es la credencial: nunca en un log.
        return "ActivationLinkIssued[organizerId=" + organizerId + "]";
    }
}
