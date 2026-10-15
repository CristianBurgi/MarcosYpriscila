package com.tuapp.eventfoto.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Redirige (302) las páginas pedidas por un host viejo (app.legacy-hosts / APP_LEGACY_HOSTS) al dominio canónico.
 * Lista vacía = no hace nada: es el interruptor de apagado.
 *
 * Solo GET/HEAD de páginas: /api/** (fetch, SSE, uploads, webhook de Mercado Pago), /actuator/** y los estáticos
 * siguen sirviéndose en el host viejo, así una pestaña ya abierta no se corta hasta que navega a otra página.
 * El destino es siempre APP_BASE_URL + URI cruda + query cruda: el host del request solo se compara, nunca se usa
 * para armar la URL, y como la URI empieza con "/" nada de lo que venga detrás (//x.com, /@x.com, %2F...) cambia
 * el host. No se loguea ruta ni query: /moderar/{token} y /activar-cuenta?token= llevan credenciales.
 * Va antes de Spring Security (WebConfig): el host viejo redirige sin pedir login ni resolver el evento.
 */
public class LegacyHostRedirectFilter extends OncePerRequestFilter {

    private static final List<String> EXCLUDED_PREFIXES = List.of(
            "/api/", "/actuator", "/css/", "/js/", "/img/", "/images/", "/uploads/", "/photos/", "/favicon.ico");

    private final Set<String> legacyHosts;
    private final String baseUrl;

    public LegacyHostRedirectFilter(String[] legacyHosts, AppUrls appUrls) {
        this.legacyHosts = Arrays.stream(legacyHosts)
                .map(h -> h.trim().toLowerCase(Locale.ROOT))
                .filter(h -> !h.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.baseUrl = appUrls.baseUrl();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        return legacyHosts.isEmpty()
                || !("GET".equals(method) || "HEAD".equals(method))
                || EXCLUDED_PREFIXES.stream().anyMatch(path::startsWith)
                || !legacyHosts.contains(request.getServerName().toLowerCase(Locale.ROOT));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String query = request.getQueryString();
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader(HttpHeaders.LOCATION, baseUrl + request.getRequestURI() + (query == null ? "" : "?" + query));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }
}
