package com.tuapp.eventfoto.event;

import java.lang.annotation.*;

/**
 * Equivalente de {@link OwnedEvent} para las rutas del moderador: entrega el evento que
 * EventAccessInterceptor resolvió desde {@code {token}}. Solo vale en rutas de ámbito MODERATOR.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ModeratedEvent {
}
