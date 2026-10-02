package com.tuapp.eventfoto.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Entrega el evento que EventAccessInterceptor ya resolvió y autorizó para esta request, a los parámetros
 * {@link OwnedEvent} (ámbito PANEL) y {@link ModeratedEvent} (ámbito MODERATOR). No consulta la base ni
 * resuelve nada por su cuenta: si el atributo no está, o la anotación no corresponde al ámbito por el que
 * entró la request, es un bug de configuración y falla ruidosamente, jamás devuelve null.
 */
@Slf4j
@Component
public class OwnedEventArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return (parameter.hasParameterAnnotation(OwnedEvent.class) || parameter.hasParameterAnnotation(ModeratedEvent.class))
                && Event.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        AccessScope expected = parameter.hasParameterAnnotation(ModeratedEvent.class) ? AccessScope.MODERATOR : AccessScope.PANEL;
        Object event = webRequest.getAttribute(EventAccessInterceptor.EVENT_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        Object scope = webRequest.getAttribute(EventAccessInterceptor.SCOPE_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (event instanceof Event resolved && scope == expected) {
            return resolved;
        }
        String message = "@" + (expected == AccessScope.MODERATOR ? "ModeratedEvent" : "OwnedEvent") + " en "
                + parameter.getExecutable().toGenericString()
                + " pero la request no trae el evento resuelto para el ámbito " + expected + " (ámbito de la request: " + scope + "): "
                + "la ruta no pasó por EventAccessInterceptor (¿está fuera de /admin/**, /api/v1/admin/**, /moderar/** y "
                + "/api/v1/moderate/**, o la anotación no corresponde a su prefijo?)";
        log.error(message);
        throw new IllegalStateException(message);
    }
}
