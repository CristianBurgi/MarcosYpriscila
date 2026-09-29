package com.tuapp.eventfoto.admin;

import com.tuapp.eventfoto.admin.dto.AuthResponseDTO;
import com.tuapp.eventfoto.admin.dto.LoginRequestDTO;
import com.tuapp.eventfoto.common.config.ClientIpResolver;
import com.tuapp.eventfoto.common.config.JwtAuthenticationFilter;
import com.tuapp.eventfoto.common.config.RateLimiterService;
import com.tuapp.eventfoto.organizer.OrganizerAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Login del organizador (antes login del único admin de la app -- ver
 * OrganizerAuthService, fase 9.1 Bloque 1). Las rutas se mantienen bajo /admin: el
 * Bloque 2 ("Mis eventos") va a rehacer este panel de todos modos.
 */
@RestController
@RequestMapping("/api/v1/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final OrganizerAuthService organizerAuthService;
    private final RateLimiterService rateLimiterService;
    private final ClientIpResolver clientIpResolver;

    @PostMapping("/login")
    public ResponseEntity<AuthResponseDTO> login(
            @Valid @RequestBody LoginRequestDTO request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {

        String clientIp = clientIpResolver.resolve(httpRequest);
        rateLimiterService.checkAdminLoginRateLimit(clientIp);

        AuthResponseDTO authResponse = organizerAuthService.authenticate(request);

        // Crear ResponseCookie HttpOnly con SameSite=Strict y Secure para máxima protección CSRF
        ResponseCookie jwtCookie = ResponseCookie.from(JwtAuthenticationFilter.ORGANIZER_COOKIE_NAME, authResponse.token())
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(authResponse.expiresInMs() / 1000)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, jwtCookie.toString());

        return ResponseEntity.ok(authResponse);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        ResponseCookie jwtCookie = ResponseCookie.from(JwtAuthenticationFilter.ORGANIZER_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(0)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, jwtCookie.toString());

        return ResponseEntity.noContent().build();
    }
}

