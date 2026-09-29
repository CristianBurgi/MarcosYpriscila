package com.tuapp.eventfoto.common.config;

import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 9.1 Bloque 1: los dos tipos de cuenta (ORGANIZER, SUPERADMIN) tienen que
 * quedar realmente aislados -- un JWT de uno no puede colarse como el otro, y una
 * sesión de organizador vieja (tokenVersion desactualizada) tiene que caer sola.
 *
 * Cada test firma el JWT a mano con la MISMA clave que usa JwtTokenProvider (en vez
 * de usar sus métodos generate*), para poder construir a propósito los casos rotos
 * que ese provider ya no permite generar (formato viejo ROLE_ADMIN, tokenVersion
 * vieja, claims a medio completar) -- así el test verifica que el rechazo es real y
 * no un artefacto de que "no existe forma de pedirle ese token al provider".
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AccountIsolationSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrganizerRepository organizerRepository;

    @Value("${security.jwt.secret}")
    private String jwtSecret;

    private SecretKey key;
    private Organizer organizer;

    @BeforeEach
    void setUp() {
        organizerRepository.deleteAll();
        organizer = organizerRepository.save(Organizer.builder().email("isolation@test.com").build());

        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            keyBytes = padded;
        }
        key = Keys.hmacShaKeyFor(keyBytes);
    }

    private String sign(io.jsonwebtoken.JwtBuilder builder) {
        Date now = new Date();
        return builder.issuedAt(now).expiration(new Date(now.getTime() + 3_600_000)).signWith(key).compact();
    }

    @Test
    @DisplayName("JWT de organizador con tokenVersion vieja (contraseña cambiada después de emitirlo) -> 401")
    void staleTokenVersionIsRejected() throws Exception {
        String staleToken = sign(Jwts.builder()
                .subject(organizer.getEmail())
                .claim("role", "ORGANIZER")
                .claim("organizerId", organizer.getId().toString())
                .claim("tokenVersion", organizer.getTokenVersion() + 1)); // versión que la BD todavía no tiene

        mockMvc.perform(get("/api/v1/admin/photos").cookie(new Cookie(JwtAuthenticationFilter.ORGANIZER_COOKIE_NAME, staleToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Token con el formato anterior a este bloque (role=ROLE_ADMIN, sin organizerId) -> 401")
    void legacyRoleAdminTokenIsRejected() throws Exception {
        String legacyToken = sign(Jwts.builder()
                .subject(organizer.getEmail())
                .claim("role", "ROLE_ADMIN")); // formato pre-Bloque 1: sin organizerId ni tokenVersion

        mockMvc.perform(get("/api/v1/admin/photos").cookie(new Cookie(JwtAuthenticationFilter.ORGANIZER_COOKIE_NAME, legacyToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Token de ORGANIZER sin organizerId/tokenVersion (payload roto a propósito) -> 401")
    void malformedOrganizerTokenIsRejected() throws Exception {
        String malformed = sign(Jwts.builder()
                .subject(organizer.getEmail())
                .claim("role", "ORGANIZER")); // rol correcto, pero sin los claims obligatorios

        mockMvc.perform(get("/api/v1/admin/photos").cookie(new Cookie(JwtAuthenticationFilter.ORGANIZER_COOKIE_NAME, malformed)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Un organizador no accede a /api/v1/superadmin/** con su propio JWT válido")
    void organizerCannotReachSuperadminRoutes() throws Exception {
        String organizerToken = sign(Jwts.builder()
                .subject(organizer.getEmail())
                .claim("role", "ORGANIZER")
                .claim("organizerId", organizer.getId().toString())
                .claim("tokenVersion", organizer.getTokenVersion()));

        mockMvc.perform(get("/api/v1/superadmin/events/free-event")
                        .header("Authorization", "Bearer " + organizerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("El JWT de superadmin no sirve como JWT de organizador en /api/v1/admin/**")
    void superadminTokenDoesNotWorkAsOrganizerToken() throws Exception {
        String superadminToken = sign(Jwts.builder()
                .subject("superadmin@eventfoto.com.ar")
                .claim("role", "SUPERADMIN"));

        // Autenticado (firma y rol válidos), pero sin el rol ORGANIZER que exige la ruta:
        // Spring Security devuelve 403 (Forbidden), no 401 -- 401 es "no autenticado".
        mockMvc.perform(get("/api/v1/admin/photos")
                        .header("Authorization", "Bearer " + superadminToken))
                .andExpect(status().isForbidden());
    }

}
