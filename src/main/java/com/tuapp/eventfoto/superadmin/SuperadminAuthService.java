package com.tuapp.eventfoto.superadmin;

import com.tuapp.eventfoto.admin.dto.AuthResponseDTO;
import com.tuapp.eventfoto.admin.dto.LoginRequestDTO;
import com.tuapp.eventfoto.common.config.JwtTokenProvider;
import com.tuapp.eventfoto.common.exception.UnauthorizedAccessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Login del superadmin: credenciales por variables de entorno (SUPERADMIN_EMAIL/
 * SUPERADMIN_PASSWORD), sin fila en BD -- a diferencia del organizador, no hay nada
 * que crear ni que un arranque de la app pueda sembrar (ver DataInitializer,
 * eliminado en el paso cero de esta fase). Comparación siempre en tiempo constante,
 * a diferencia de la versión anterior de este login que comparaba texto plano con
 * String.equals().
 */
@Slf4j
@Service
public class SuperadminAuthService {

    private final String superadminEmail;
    private final String superadminPassword;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public SuperadminAuthService(
            @Value("${security.superadmin.email}") String superadminEmail,
            @Value("${security.superadmin.password}") String superadminPassword,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider) {
        this.superadminEmail = superadminEmail.trim().toLowerCase();
        this.superadminPassword = superadminPassword;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    public AuthResponseDTO authenticate(LoginRequestDTO request) {
        String inputEmail = request.email() != null ? request.email().trim().toLowerCase() : "";
        String inputPassword = request.password() != null ? request.password() : "";

        if (!superadminEmail.equals(inputEmail) || !passwordMatches(inputPassword)) {
            log.warn("Intento de login de superadmin fallido para el email: {}", inputEmail);
            throw new UnauthorizedAccessException("Credenciales inválidas");
        }

        String token = jwtTokenProvider.generateSuperadminToken(superadminEmail);
        log.info("Autenticación exitosa para el superadmin: {}", superadminEmail);
        return new AuthResponseDTO(token, superadminEmail, jwtTokenProvider.superadminExpirationMs());
    }

    private boolean passwordMatches(String inputPassword) {
        if (isBcryptHash(superadminPassword)) {
            return passwordEncoder.matches(inputPassword, superadminPassword);
        }
        return constantTimeEquals(inputPassword, superadminPassword);
    }

    private static boolean isBcryptHash(String value) {
        return value.startsWith("$2a$") || value.startsWith("$2b$") || value.startsWith("$2y$");
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
