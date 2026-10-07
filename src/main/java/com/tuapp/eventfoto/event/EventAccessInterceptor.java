package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.common.config.ClientIpResolver;
import com.tuapp.eventfoto.common.config.TokenMasker;
import com.tuapp.eventfoto.common.exception.EventExpiredException;
import com.tuapp.eventfoto.common.exception.ResourceNotFoundException;
import com.tuapp.eventfoto.common.exception.WizardRequiredException;
import com.tuapp.eventfoto.moderation.ModeratorRateLimiter;
import jakarta.servlet.DispatcherType;
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

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Único punto que resuelve el evento de una request y verifica el acceso, para las dos puertas:
 * <ul>
 *   <li>PANEL ({@code /admin/**}, {@code /api/v1/admin/**}): el evento sale de {@code {slug}};</li>
 *   <li>MODERATOR ({@code /moderar/**}, {@code /api/v1/moderate/**}): el evento sale de {@code {token}}.</li>
 * </ul>
 * Pregunta a las {@link EventAccessPolicy} y guarda el {@link Event} como atributo de la request (lo
 * leen @OwnedEvent y @ModeratedEvent).
 *
 * Un endpoint nuevo bajo esos prefijos queda protegido sin que nadie tenga que acordarse
 * de nada. Si la ruta no tiene su variable ({slug} / {token}) y no figura en
 * {@link AdminRouteExceptions}, se CIERRA (404 + log.error con la ruta ENMASCARADA): nunca queda abierta.
 * AdminRouteEnumerationTest hace que además rompa el build.
 *
 * En el panel, con el álbum vencido, toda ruta del evento responde la pantalla "no disponible" (vistas) o 410
 * EVENT_EXPIRED (API). Con el wizard del evento sin completar, toda ruta fuera de {@link WizardRouteExceptions}
 * redirige al wizard (vistas) o responde 409 WIZARD_REQUIRED (API).
 *
 * "No existe" y "no es tuyo" (y, en el moderador, "mal formado") lanzan la misma excepción con el mismo
 * mensaje: el 404 no permite descubrir qué slugs o tokens existen. En el moderador cada intento fallido se
 * cuenta por IP ({@link ModeratorRateLimiter}) y pasado el tope responde 429.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventAccessInterceptor implements HandlerInterceptor {

    public static final String EVENT_ATTRIBUTE = EventAccessInterceptor.class.getName() + ".EVENT";
    public static final String SCOPE_ATTRIBUTE = EventAccessInterceptor.class.getName() + ".SCOPE";
    public static final String EVENT_NOT_FOUND_MESSAGE = "Evento no encontrado";

    private final EventRepository eventRepository;
    private final List<EventAccessPolicy> policies;
    private final ModeratorRateLimiter moderatorRateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final UploadWindow uploadWindow;

    @Override
    @SuppressWarnings("unchecked")
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            // Redespacho al terminar un SseEmitter: el acceso ya se verificó en el despacho original (y el
            // token pudo haberse regenerado mientras tanto, que es justo cuando se cierran esos streams).
            return true;
        }

        String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        AccessScope scope = AccessScope.ofPattern(pattern != null ? pattern : request.getRequestURI());

        if (scope == AccessScope.PANEL && AdminRouteExceptions.isException(pattern)) {
            return true;
        }

        Map<String, String> pathVariables =
                (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String locator = pathVariables != null ? pathVariables.get(scope.locatorVariable()) : null;

        if (!(handler instanceof HandlerMethod)) {
            // Recurso estático o ruta inventada bajo /admin o /moderar: no es un controller nuestro, así que no es
            // un bug de configuración (y no debe llegar a Sentry como error). Igual se cierra.
            log.warn("Request a {} {} bajo {} sin controller (handler {}): se responde 404.",
                    request.getMethod(), TokenMasker.mask(request.getRequestURI()), scope,
                    handler == null ? "null" : handler.getClass().getSimpleName());
            throw new ResourceNotFoundException(EVENT_NOT_FOUND_MESSAGE);
        }

        if (locator == null) {
            // Un controller sin su variable y fuera de AdminRouteExceptions: eso SÍ es un bug.
            log.error("Ruta {} sin variable de evento y fuera de AdminRouteExceptions: {} {} (patrón '{}'). Se responde 404. "
                    + "Agregale {} a la ruta o, si de verdad no opera sobre un evento, declarala en AdminRouteExceptions con su motivo.",
                    scope, request.getMethod(), TokenMasker.mask(request.getRequestURI()), pattern,
                    "{" + scope.locatorVariable() + "}");
            throw new ResourceNotFoundException(EVENT_NOT_FOUND_MESSAGE);
        }

        Event event = switch (scope) {
            case PANEL -> eventRepository.findBySlug(locator.toLowerCase().trim()).orElse(null);
            case MODERATOR -> resolveByToken(request, locator);
        };
        if (event == null) {
            throw new ResourceNotFoundException(EVENT_NOT_FOUND_MESSAGE);
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        AccessRequest accessRequest = new AccessRequest(scope, authentication);
        boolean allowed = policies.stream().anyMatch(policy -> policy.canAccess(accessRequest, event));
        if (!allowed) {
            if (scope == AccessScope.MODERATOR) {
                moderatorRateLimiter.recordInvalidAttempt(clientIpResolver.resolve(request));
            }
            throw new ResourceNotFoundException(EVENT_NOT_FOUND_MESSAGE);
        }

        request.setAttribute(EVENT_ATTRIBUTE, event);
        request.setAttribute(SCOPE_ATTRIBUTE, scope);

        // Después del acceso a propósito: un evento ajeno sigue dando el 404 de arriba, nunca estos bloqueos.
        // Álbum vencido: la vista muestra "no disponible" y la API responde 410 EVENT_EXPIRED (el moderador ya quedó
        // afuera en la policy: ModeratorTokenPolicy.isModeratable).
        if (scope == AccessScope.PANEL && uploadWindow.isExpired(event)) {
            throw new EventExpiredException();
        }
        if (scope == AccessScope.PANEL && event.getWizardCompletedAt() == null && !WizardRouteExceptions.isException(pattern)) {
            if (pattern.startsWith("/admin/")) {
                response.sendRedirect("/admin/eventos/" + event.getSlug() + "/wizard");
                return false;
            }
            throw new WizardRequiredException();
        }
        return true;
    }

    /** Token inválido, mal formado o inexistente: mismo resultado (null -> 404) y cuenta como intento fallido de la IP. */
    private Event resolveByToken(HttpServletRequest request, String token) {
        String clientIp = clientIpResolver.resolve(request);
        moderatorRateLimiter.checkNotBlocked(clientIp);

        Optional<Event> event = ModeratorTokens.isWellFormed(token)
                ? eventRepository.findByModeratorToken(token)
                : Optional.empty();
        if (event.isEmpty()) {
            moderatorRateLimiter.recordInvalidAttempt(clientIp);
        }
        return event.orElse(null);
    }
}
