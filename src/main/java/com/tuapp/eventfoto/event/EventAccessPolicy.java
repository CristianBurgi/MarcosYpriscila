package com.tuapp.eventfoto.event;

import org.springframework.security.core.Authentication;

/**
 * Decide si quien hace la request puede operar sobre un evento. EventAccessInterceptor
 * consulta TODAS las políticas registradas y da acceso si alguna lo concede.
 *
 * Hoy hay una sola ({@link OrganizerOwnerPolicy}: el organizador dueño). El link de
 * moderador de la 9.2 se suma como otro bean que implemente esta interfaz, sin tocar el
 * interceptor.
 */
public interface EventAccessPolicy {

    boolean canAccess(Authentication authentication, Event event);
}
