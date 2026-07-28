package com.tuapp.eventfoto.admin;

import com.tuapp.eventfoto.admin.dto.AuthResponseDTO;
import com.tuapp.eventfoto.admin.dto.LoginRequestDTO;
import com.tuapp.eventfoto.common.config.JwtTokenProvider;
import com.tuapp.eventfoto.common.exception.UnauthorizedAccessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AdminAuthService {

    private final String adminEmail;
    private final String adminPassword;
    private final long expirationMs;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public AdminAuthService(
            @Value("${security.admin.email}") String adminEmail,
            @Value("${security.admin.password}") String adminPassword,
            @Value("${security.jwt.expiration-ms}") long expirationMs,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider) {
        this.adminEmail = adminEmail.trim().toLowerCase();
        this.adminPassword = adminPassword;
        this.expirationMs = expirationMs;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * Valida credenciales contra las variables de entorno de admin.
     * Soporta contraseñas en texto plano o hasheadas con BCrypt.
     */
    public AuthResponseDTO authenticate(LoginRequestDTO request) {
        String inputEmail = request.email() != null ? request.email().trim().toLowerCase() : "";
        String inputPassword = request.password() != null ? request.password() : "";

        if (!adminEmail.equalsIgnoreCase(inputEmail)) {
            log.warn("Intento de login fallido para el email: {}", inputEmail);
            throw new UnauthorizedAccessException("Credenciales de administrador inválidas");
        }

        boolean passwordMatches;
        if (adminPassword.startsWith("$2a$") || adminPassword.startsWith("$2b$") || adminPassword.startsWith("$2y$")) {
            passwordMatches = passwordEncoder.matches(inputPassword, adminPassword);
        } else {
            // Comparación de texto plano o hash coincidente
            passwordMatches = adminPassword.equals(inputPassword) || passwordEncoder.matches(inputPassword, passwordEncoder.encode(adminPassword));
        }

        if (!passwordMatches) {
            log.warn("Contraseña incorrecta para el usuario administrador: {}", inputEmail);
            throw new UnauthorizedAccessException("Credenciales de administrador inválidas");
        }

        String token = jwtTokenProvider.generateToken(adminEmail);
        log.info("Autenticación exitosa para el administrador: {}", adminEmail);

        return new AuthResponseDTO(token, adminEmail, expirationMs);
    }
}
