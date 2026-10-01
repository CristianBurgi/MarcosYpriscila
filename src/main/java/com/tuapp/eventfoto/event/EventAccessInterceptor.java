package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.common.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;
import java.util.Map;

/**
 * Protege POR DEFECTO todo /admin/** y /api/v1/admin/**: resuelve el evento desde la
 * variable de ruta {slug}, pregunta a las {@link EventAccessPolicy} si el principal tiene
 * acceso y guarda el {@link Event} como atributo de la request (lo lee @OwnedEvent).
 *
 * Un endpoint nuevo bajo esos prefijos queda protegido sin que nadie tenga que acordarse
 * de nada. Si la ruta no tiene {slug} y no figura en {@link AdminRouteExceptions}, se
 * CIERRA (404 + log.error con la ruta): nunca queda abierta. AdminRouteEnumerationTest
 * hace que además rompa el build.
 *
 * "No existe" y "no es tuyo" lanzan la misma excepción con el mismo mensaje: el 404 no
 * permite descubrir qué slugs existen.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventAccessInterceptor implements HandlerInterceptor {

    public static final String EVENT_ATTRIBUTE = EventAccessInterceptor.class.getName() + ".EVENT";
    public static final String EVENT_NOT_FOUND_MESSAGE = "Evento no encontrado";

    private final EventRepository eventRepository;
    private final List<EventAccessPolicy> policies;

    @Override
    @SuppressWarnings("unchecked")
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);

        if (AdminRouteExceptions.isException(pattern)) {
            return true;
        }

        Map<String, String> pathVariables =
                (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String slug = pathVariables != null ? pathVariables.get("slug") : null;

        if (!(handler instanceof HandlerMethod) || slug == null) {
            log.error("Ruta del panel sin {slug} y fuera de AdminRouteExceptions: {} {} (patrón '{}'). Se responde 404. "
                    + "Agregale {slug} a la ruta o, si de verdad no opera sobre un evento, declarala en AdminRouteExceptions con su motivo.",
                    request.getMethod(), request.getRequestURI(), pattern);
            throw new ResourceNotFoundException(EVENT_NOT_FOUND_MESSAGE);
        }

        Event event = eventRepository.findBySlug(slug.toLowerCase().trim())
                .orElseThrow(() -> new ResourceNotFoundException(EVENT_NOT_FOUND_MESSAGE));

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean allowed = policies.stream().anyMatch(policy -> policy.canAccess(authentication, event));
        if (!allowed) {
            throw new ResourceNotFoundException(EVENT_NOT_FOUND_MESSAGE);
        }

        request.setAttribute(EVENT_ATTRIBUTE, event);
        return true;
    }
}
