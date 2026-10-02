package com.tuapp.eventfoto.event;

import org.springframework.stereotype.Component;

/**
 * Tercer tipo de principal: quien tiene el link de moderador. La credencial ES el token de la URL:
 * si el interceptor resolvió el evento por token, la policy solo confirma que la request entró por la
 * puerta del moderador (nunca concede nada en el panel) y que el evento sigue siendo moderable.
 */
@Component
public class ModeratorTokenPolicy implements EventAccessPolicy {

    @Override
    public boolean canAccess(AccessRequest request, Event event) {
        return request.scope() == AccessScope.MODERATOR && isModeratable(event);
    }

    /**
     * Único lugar que decide si un evento admite moderación. Hoy siempre: el link no vence y
     * isActive=false (recepción cerrada) NO cierra la moderación. La regla de vencimiento se define en 9.6;
     * cuando exista, se cambia acá y todos los 404 del moderador la respetan.
     */
    public static boolean isModeratable(Event event) {
        return event != null;
    }
}
