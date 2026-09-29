package com.tuapp.eventfoto.organizer;

import com.tuapp.eventfoto.admin.dto.AuthResponseDTO;
import com.tuapp.eventfoto.admin.dto.LoginRequestDTO;
import com.tuapp.eventfoto.common.config.JwtTokenProvider;
import com.tuapp.eventfoto.common.exception.UnauthorizedAccessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login del organizador contra la tabla organizer (fase 9.1 Bloque 1; antes era un
 * único admin validado contra variables de entorno -- ver SuperadminAuthService para
 * ese rol, que ahora es una cuenta separada).
 *
 * Un mismo mensaje genérico para email inexistente, cuenta sin activar y contraseña
 * incorrecta: distinguir "la cuenta existe pero no está activada" filtraría qué
 * emails están registrados.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizerAuthService {

    private static final String INVALID_CREDENTIALS_MESSAGE = "Credenciales inválidas";

    private final OrganizerRepository organizerRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional(readOnly = true)
    public AuthResponseDTO authenticate(LoginRequestDTO request) {
        String email = request.email() != null ? request.email().trim().toLowerCase() : "";
        String password = request.password() != null ? request.password() : "";

        Organizer organizer = organizerRepository.findByEmailIgnoreCase(email).orElse(null);
        if (organizer == null || !organizer.isActivated() || !passwordEncoder.matches(password, organizer.getPasswordHash())) {
            log.warn("Intento de login de organizador fallido para el email: {}", email);
            throw new UnauthorizedAccessException(INVALID_CREDENTIALS_MESSAGE);
        }

        String token = jwtTokenProvider.generateOrganizerToken(organizer.getId(), organizer.getEmail(), organizer.getTokenVersion());
        log.info("Autenticación exitosa para el organizador: {}", organizer.getId());
        return new AuthResponseDTO(token, organizer.getEmail(), jwtTokenProvider.organizerExpirationMs());
    }
}
