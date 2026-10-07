package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.common.exception.EventExpiredException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * Lado público de un álbum vencido ({@link UploadWindow#isExpired}): las páginas {@code /e/{slug}/...} muestran
 * "Este álbum ya no está disponible." y la API {@code /api/v1/events/{slug}/...} responde 410 EVENT_EXPIRED.
 *
 * Va por ruta y no dentro de EventService.getEventEntityBySlug a propósito: ese método también lo usan el panel
 * (dashboard, ZIP, PDF, toggle), que tiene su propio chequeo en {@link EventAccessInterceptor}. Un slug inexistente
 * sigue de largo y el controller responde su 404 de siempre.
 */
@Component
@RequiredArgsConstructor
public class PublicEventExpiryInterceptor implements HandlerInterceptor {

    public static final String[] PATHS = {"/e/*", "/e/*/**", "/api/v1/events/*", "/api/v1/events/*/**"};

    private final EventRepository eventRepository;
    private final UploadWindow uploadWindow;

    @Override
    @SuppressWarnings("unchecked")
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return true; // redespacho de un SSE: ya se chequeó en el despacho original
        }
        Map<String, String> pathVariables =
                (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String slug = pathVariables != null ? pathVariables.get("slug") : null;
        if (slug != null && eventRepository.findBySlug(slug.toLowerCase().trim()).filter(uploadWindow::isExpired).isPresent()) {
            throw new EventExpiredException();
        }
        return true;
    }
}
