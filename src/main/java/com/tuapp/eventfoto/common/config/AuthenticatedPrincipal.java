package com.tuapp.eventfoto.common.config;

import java.util.UUID;

/**
 * Lo que se extrae de un JWT ya validado (firma + expiración + forma correcta para
 * su rol). {@code organizerId}/{@code tokenVersion} solo se completan para
 * {@link AccountRole#ORGANIZER}; para {@link AccountRole#SUPERADMIN} quedan null.
 */
public record AuthenticatedPrincipal(String subject, AccountRole role, UUID organizerId, Integer tokenVersion) {
}
