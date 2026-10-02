package com.tuapp.eventfoto.event;

import org.springframework.security.core.Authentication;

/** Lo que las {@link EventAccessPolicy} saben de la request: por qué puerta entra y quién está logueado (si alguien). */
public record AccessRequest(AccessScope scope, Authentication authentication) {
}
