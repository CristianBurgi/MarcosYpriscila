package com.tuapp.eventfoto.event;

/**
 * Decide si quien hace la request puede operar sobre un evento. EventAccessInterceptor
 * consulta TODAS las políticas registradas y da acceso si alguna lo concede.
 *
 * Hay dos: {@link OrganizerOwnerPolicy} (el organizador dueño, ámbito PANEL) y
 * {@link ModeratorTokenPolicy} (el link de moderador, ámbito MODERATOR). Cada una solo concede
 * en su ámbito, así que una credencial de un tipo nunca abre las rutas del otro.
 */
public interface EventAccessPolicy {

    boolean canAccess(AccessRequest request, Event event);
}
