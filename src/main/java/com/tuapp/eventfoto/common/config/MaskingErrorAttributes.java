package com.tuapp.eventfoto.common.config;

import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;

import java.util.Map;

/**
 * Los errores que NO pasan por GlobalExceptionHandler (los que Tomcat o la cadena de seguridad mandan a /error)
 * los arma Spring Boot con DefaultErrorAttributes, que incluye el "path" y a veces el mensaje original. Bajo el
 * lado del moderador esos textos llevarían el token: se enmascaran.
 */
@Component
public class MaskingErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, org.springframework.boot.web.error.ErrorAttributeOptions options) {
        Map<String, Object> attributes = super.getErrorAttributes(webRequest, options);
        for (String key : new String[]{"path", "message", "error", "trace"}) {
            if (attributes.get(key) instanceof String value) {
                attributes.put(key, TokenMasker.mask(value));
            }
        }
        return attributes;
    }
}
