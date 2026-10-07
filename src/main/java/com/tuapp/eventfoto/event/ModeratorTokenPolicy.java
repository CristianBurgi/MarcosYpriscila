package com.tuapp.eventfoto.event;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Tercer tipo de principal: quien tiene el link de moderador. La credencial ES el token de la URL:
 * si el interceptor resolvió el evento por token, la policy solo confirma que la request entró por la
 * puerta del moderador (nunca concede nada en el panel) y que el evento sigue siendo moderable.
 */
@Component
@RequiredArgsConstructor
public class ModeratorTokenPolicy implements EventAccessPolicy {

    private final UploadWindow uploadWindow;

    @Override
    public boolean canAccess(AccessRequest request, Event event) {
        return request.scope() == AccessScope.MODERATOR && isModeratable(event);
    }

    /**
     * Único lugar que decide si un evento admite moderación. isActive=false (recepción cerrada) NO cierra la
     * moderación; el vencimiento del álbum sí (9.6): desde la fecha de borrado el link da el 404 de siempre.
     */
    public boolean isModeratable(Event event) {
        return event != null && !uploadWindow.isExpired(event);
    }
}
