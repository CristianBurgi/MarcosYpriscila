package com.tuapp.eventfoto.event;

import java.lang.annotation.*;

/**
 * Parámetro de controller de tipo {@link Event}: entrega el evento que EventAccessInterceptor
 * ya resolvió desde {@code {slug}} y autorizó para el organizador logueado. No consulta nada
 * por su cuenta; si la ruta no pasó por el interceptor falla ruidosamente.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OwnedEvent {
}
