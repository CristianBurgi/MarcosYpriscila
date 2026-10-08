package com.tuapp.eventfoto.superadmin;

import com.tuapp.eventfoto.admin.dto.AuthResponseDTO;
import com.tuapp.eventfoto.admin.dto.LoginRequestDTO;
import com.tuapp.eventfoto.common.config.ClientIpResolver;
import com.tuapp.eventfoto.common.config.JwtAuthenticationFilter;
import com.tuapp.eventfoto.common.config.RateLimiterService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/superadmin/auth")
@RequiredArgsConstructor
public class SuperadminAuthController {

    private final SuperadminAuthService superadminAuthService;
    private final RateLimiterService rateLimiterService;
    private final ClientIpResolver clientIpResolver;

    @PostMapping("/login")
    public ResponseEntity<AuthResponseDTO> login(
            @Valid @RequestBody LoginRequestDTO request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {

        rateLimiterService.checkSuperadminLoginRateLimit(clientIpResolver.resolve(httpRequest));

        AuthResponseDTO authResponse = superadminAuthService.authenticate(request);

        ResponseCookie jwtCookie = ResponseCookie.from(JwtAuthenticationFilter.SUPERADMIN_COOKIE_NAME, authResponse.token())
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(authResponse.expiresInMs() / 1000)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, jwtCookie.toString());
        return ResponseEntity.ok(authResponse);
    }

    @PostMapping(path = "/logout", consumes = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        ResponseCookie jwtCookie = ResponseCookie.from(JwtAuthenticationFilter.SUPERADMIN_COOKIE_NAME, "")
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
