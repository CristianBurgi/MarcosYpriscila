package com.tuapp.eventfoto.admin;

import com.tuapp.eventfoto.admin.dto.AuthResponseDTO;
import com.tuapp.eventfoto.admin.dto.LoginRequestDTO;
import com.tuapp.eventfoto.common.config.JwtAuthenticationFilter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    @PostMapping("/login")
    public ResponseEntity<AuthResponseDTO> login(
            @Valid @RequestBody LoginRequestDTO request,
            HttpServletResponse response) {

        AuthResponseDTO authResponse = adminAuthService.authenticate(request);

        // Crear cookie HttpOnly con el JWT para que Thymeleaf pueda navegar autenticado
        Cookie jwtCookie = new Cookie(JwtAuthenticationFilter.COOKIE_NAME, authResponse.token());
        jwtCookie.setHttpOnly(true);
        jwtCookie.setPath("/");
        jwtCookie.setSecure(true);
        jwtCookie.setMaxAge((int) (authResponse.expiresInMs() / 1000));
        response.addCookie(jwtCookie);

        return ResponseEntity.ok(authResponse);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        Cookie jwtCookie = new Cookie(JwtAuthenticationFilter.COOKIE_NAME, null);
        jwtCookie.setHttpOnly(true);
        jwtCookie.setPath("/");
        jwtCookie.setMaxAge(0);
        response.addCookie(jwtCookie);

        return ResponseEntity.noContent().build();
    }
}
