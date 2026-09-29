package com.tuapp.eventfoto.common.config;

import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Fase 9.1 Bloque 1: hay dos cookies separadas (nombres distintos, mismo path "/")
 * para que un mismo navegador pueda tener sesión de organizador Y de superadmin al
 * mismo tiempo, sin que loguearse en una pise la cookie de la otra -- no hace falta
 * usar ventanas separadas. Cuál cookie se lee depende del prefijo de la URL
 * (/superadmin/** usa la de superadmin, todo lo demás la de organizador); el header
 * Authorization (para API clients / tests) no depende de ninguna cookie.
 *
 * Para ORGANIZER, la validación no termina en la firma del JWT: se compara
 * tokenVersion del token contra el valor actual en BD (una consulta por request).
 * Se acepta esa consulta extra a esta escala -- es la única forma de poder invalidar
 * sesiones de organizador sin mantener una lista de revocación aparte. SUPERADMIN no
 * tiene fila en BD, así que no hay nada que comparar: su única defensa contra una
 * sesión comprometida es la expiración corta del token (ver JwtTokenProvider) y que
 * rotar JWT_SECRET invalida todas las sesiones (de los dos roles) de una.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String ORGANIZER_COOKIE_NAME = "JWT-TOKEN";
    public static final String SUPERADMIN_COOKIE_NAME = "SUPERADMIN-JWT-TOKEN";

    private final JwtTokenProvider jwtTokenProvider;
    private final OrganizerRepository organizerRepository;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        try {
            String jwt = resolveToken(request);
            if (StringUtils.hasText(jwt)) {
                jwtTokenProvider.parsePrincipal(jwt).ifPresent(principal -> authenticate(principal, request));
            }
        } catch (Exception ex) {
            log.error("No se pudo establecer la autenticación del usuario en el contexto de seguridad", ex);
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(AuthenticatedPrincipal principal, HttpServletRequest request) {
        if (principal.role() == AccountRole.SUPERADMIN) {
            setAuthentication(principal.subject(), "ROLE_SUPERADMIN", request);
            return;
        }

        Optional<Organizer> organizer = organizerRepository.findById(principal.organizerId());
        if (organizer.isEmpty() || organizer.get().getTokenVersion() != principal.tokenVersion()) {
            log.warn("JWT de organizador con tokenVersion desactualizada u organizador inexistente ({})", principal.organizerId());
            return;
        }
        setAuthentication(principal.organizerId(), "ROLE_ORGANIZER", request);
    }

    private void setAuthentication(Object principal, String authority, HttpServletRequest request) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority(authority)));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }

        String cookieName = request.getRequestURI().startsWith("/superadmin") || request.getRequestURI().startsWith("/api/v1/superadmin")
                ? SUPERADMIN_COOKIE_NAME
                : ORGANIZER_COOKIE_NAME;

        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (cookieName.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                    return cookie.getValue();
                }
            }
        }

        return null;
    }
}
