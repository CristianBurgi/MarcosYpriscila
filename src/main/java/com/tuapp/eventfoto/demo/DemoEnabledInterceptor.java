package com.tuapp.eventfoto.demo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * Interruptor de la demo (app.demo.enabled, true por defecto), por si alguien abusa antes de que haya tiempo de
 * algo mejor. Apagada: las páginas muestran "La demo no está disponible en este momento" y la API responde 503.
 */
@Component
public class DemoEnabledInterceptor implements HandlerInterceptor {

    public static final String[] PATHS = {"/demo", "/demo/**", "/api/v1/demo/**"};
    static final String MESSAGE = "La demo no está disponible en este momento.";

    private final boolean enabled;

    public DemoEnabledInterceptor(@Value("${app.demo.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (enabled) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (request.getRequestURI().startsWith("/api/")) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"status\":503,\"error\":\"Service Unavailable\",\"message\":\"" + MESSAGE + "\"}");
        } else {
            response.setContentType(MediaType.TEXT_HTML_VALUE);
            try (var page = getClass().getResourceAsStream("/templates/demo/unavailable.html")) {
                response.getWriter().write(new String(page.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return false;
    }
}
