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
 * Entrega el evento que EventAccessInterceptor ya resolvió y autorizó para esta request.
 * No consulta la base ni resuelve nada por su cuenta: si el atributo no está, es un bug de
 * configuración (la ruta no pasó por el interceptor) y falla ruidosamente, jamás devuelve
 * null.
 */
@Slf4j
@Component
public class OwnedEventArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(OwnedEvent.class) && Event.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Object event = webRequest.getAttribute(EventAccessInterceptor.EVENT_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (event instanceof Event resolved) {
            return resolved;
        }
        String message = "@OwnedEvent en " + parameter.getExecutable().toGenericString()
                + " pero la request no trae el evento resuelto: la ruta no pasó por EventAccessInterceptor "
                + "(¿está fuera de /admin/** y /api/v1/admin/**, o el interceptor no está registrado?)";
        log.error(message);
        throw new IllegalStateException(message);
    }
}
