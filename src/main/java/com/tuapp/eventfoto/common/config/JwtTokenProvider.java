package com.tuapp.eventfoto.common.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Fase 9.1 Bloque 1: el JWT ahora tiene dos formas según el rol. ORGANIZER lleva
 * {@code organizerId}/{@code tokenVersion} (JwtAuthenticationFilter compara este
 * último contra la BD en cada request -- ver esa clase para el porqué). SUPERADMIN
 * no tiene fila en BD que consultar, así que su única defensa es una expiración
 * corta (ver {@link #superadminExpirationMs}) y que rotar JWT_SECRET invalida todo.
 *
 * {@link #parsePrincipal(String)} es el único punto de entrada para leer un token:
 * si el rol no es exactamente "ORGANIZER" o "SUPERADMIN", o a un ORGANIZER le falta
 * organizerId/tokenVersion, devuelve Optional.empty() -- esto es lo que hace que un
 * token con el formato viejo (role=ROLE_ADMIN, sin organizerId) quede rechazado.
 */
@Slf4j
@Component
public class JwtTokenProvider {

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_ORGANIZER_ID = "organizerId";
    private static final String CLAIM_TOKEN_VERSION = "tokenVersion";

    private final SecretKey key;
    private final long organizerExpirationMs;
    private final long superadminExpirationMs;

    public JwtTokenProvider(
            @Value("${security.jwt.secret}") String secret,
            @Value("${security.jwt.expiration-ms}") long organizerExpirationMs,
            @Value("${security.superadmin.jwt.expiration-ms}") long superadminExpirationMs) {

        // Asegurar que la clave tenga al menos 32 bytes para HMAC-SHA256
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, Math.min(keyBytes.length, 32));
            this.key = Keys.hmacShaKeyFor(padded);
        } else {
            this.key = Keys.hmacShaKeyFor(keyBytes);
        }
        this.organizerExpirationMs = organizerExpirationMs;
        this.superadminExpirationMs = superadminExpirationMs;
    }

    public long organizerExpirationMs() {
        return organizerExpirationMs;
    }

    public long superadminExpirationMs() {
        return superadminExpirationMs;
    }

    public String generateOrganizerToken(UUID organizerId, String email, int tokenVersion) {
        Date now = new Date();
        return Jwts.builder()
                .subject(email)
                .claim(CLAIM_ROLE, AccountRole.ORGANIZER.name())
                .claim(CLAIM_ORGANIZER_ID, organizerId.toString())
                .claim(CLAIM_TOKEN_VERSION, tokenVersion)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + organizerExpirationMs))
                .signWith(key)
                .compact();
    }

    public String generateSuperadminToken(String email) {
        Date now = new Date();
        return Jwts.builder()
                .subject(email)
                .claim(CLAIM_ROLE, AccountRole.SUPERADMIN.name())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + superadminExpirationMs))
                .signWith(key)
                .compact();
    }

    /**
     * Valida firma + expiración y, si el token tiene la forma correcta para el rol
     * que declara, devuelve el principal. Cualquier otra cosa (firma inválida,
     * vencido, rol desconocido, ORGANIZER sin organizerId/tokenVersion) es
     * Optional.empty() -- no se distingue el motivo hacia el caller a propósito.
     */
    public Optional<AuthenticatedPrincipal> parsePrincipal(String token) {
        Claims claims;
        try {
            claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Token JWT inválido o expirado: {}", e.getMessage());
            return Optional.empty();
        }

        String roleValue = claims.get(CLAIM_ROLE, String.class);
        AccountRole role;
        try {
            role = AccountRole.valueOf(roleValue);
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("Token JWT con rol desconocido o ausente: '{}'", roleValue);
            return Optional.empty();
        }

        if (role == AccountRole.SUPERADMIN) {
            return Optional.of(new AuthenticatedPrincipal(claims.getSubject(), role, null, null));
        }

        String organizerIdValue = claims.get(CLAIM_ORGANIZER_ID, String.class);
        Integer tokenVersion = claims.get(CLAIM_TOKEN_VERSION, Integer.class);
        if (organizerIdValue == null || tokenVersion == null) {
            log.warn("Token JWT de ORGANIZER sin organizerId/tokenVersion");
            return Optional.empty();
        }
        UUID organizerId;
        try {
            organizerId = UUID.fromString(organizerIdValue);
        } catch (IllegalArgumentException e) {
            log.warn("Token JWT de ORGANIZER con organizerId inválido: '{}'", organizerIdValue);
            return Optional.empty();
        }
        return Optional.of(new AuthenticatedPrincipal(claims.getSubject(), role, organizerId, tokenVersion));
    }
}
