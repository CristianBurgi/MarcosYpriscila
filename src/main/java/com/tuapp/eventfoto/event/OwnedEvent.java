package com.tuapp.eventfoto.event;

import java.lang.annotation.*;

/**
 * Parámetro de controller de tipo {@link Event}: se resuelve desde la variable de ruta
 * {@code {slug}} y el organizador autenticado, o responde 404. Todo endpoint del panel
 * que trabaja sobre un evento lo declara así en lugar de resolver el evento por su cuenta.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OwnedEvent {
}
