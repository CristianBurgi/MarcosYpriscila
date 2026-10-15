package com.tuapp.eventfoto.organizer;

import com.tuapp.eventfoto.common.exception.InvalidActivationTokenException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Emite y valida tokens de un solo uso del organizador: activación de cuenta (9.1) y recuperación de contraseña
 * (9.2-B), con el mismo mecanismo y la misma tabla. El valor en claro solo existe acá, en memoria, el instante en
 * que se genera -- lo que se persiste es su hash SHA-256.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizerTokenService {

    /**
     * Fase 9.1 Bloque 1: el link de activación lo manda el superadmin (y desde la 9.2-B también llega por mail),
     * así que tiene que dar tiempo real a que la persona lo vea y lo abra sin quedar dando vueltas indefinidamente.
     */
    static final Duration ACTIVATION_TTL = Duration.ofDays(7);

    /** Recuperación de contraseña: lo pide la misma persona y lo usa en el momento; un link de mail vivo es un riesgo. */
    static final Duration PASSWORD_RESET_TTL = Duration.ofHours(1);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder RAW_TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final String INVALID_TOKEN_MESSAGE = "El link no es válido, ya se usó o venció.";

    private final OrganizerTokenRepository organizerTokenRepository;
    private final Clock clock;

    public record IssuedToken(String rawToken, Instant expiresAt) {
    }

    static Duration ttl(OrganizerTokenPurpose purpose) {
        return switch (purpose) {
            case ACCOUNT_ACTIVATION -> ACTIVATION_TTL;
            case PASSWORD_RESET -> PASSWORD_RESET_TTL;
        };
    }

    /**
     * Genera un token nuevo e invalida (vía {@code used_at}) cualquier token previo sin
     * usar del mismo organizador y propósito -- "generar uno nuevo invalida el anterior".
     */
    @Transactional
    public IssuedToken issue(Organizer organizer, OrganizerTokenPurpose purpose) {
        List<OrganizerToken> previous = organizerTokenRepository
                .findByOrganizerIdAndPurposeAndUsedAtIsNull(organizer.getId(), purpose);
        Instant now = clock.instant();
        previous.forEach(token -> token.setUsedAt(now));
        organizerTokenRepository.saveAll(previous);

        String rawToken = generateRawToken();
        Instant expiresAt = now.plus(ttl(purpose));

        OrganizerToken token = OrganizerToken.builder()
                .organizer(organizer)
                .purpose(purpose)
                .tokenHash(hash(rawToken))
                .expiresAt(expiresAt)
                .build();
        organizerTokenRepository.save(token);

        log.info("Token de {} emitido para organizer {} (vence {})", purpose, organizer.getId(), expiresAt);
        return new IssuedToken(rawToken, expiresAt);
    }

    /**
     * Dueño de un token usable, SIN consumirlo: para validar la contraseña nueva contra su email antes de gastar
     * el link (una contraseña rechazada no lo consume). Mismo mensaje genérico que {@link #consume}.
     */
    @Transactional(readOnly = true)
    public Organizer peek(String rawToken, OrganizerTokenPurpose purpose) {
        return findUsable(rawToken, purpose).getOrganizer();
    }

    /**
     * Valida y consume un token: si no existe, ya se usó, venció o el propósito no
     * coincide, rechaza con el mismo mensaje genérico en todos los casos. El consumo es un UPDATE condicional
     * (used_at IS NULL): si dos pedidos llegan con el mismo token, solo uno lo gasta.
     */
    @Transactional
    public Organizer consume(String rawToken, OrganizerTokenPurpose purpose) {
        OrganizerToken token = findUsable(rawToken, purpose);
        if (organizerTokenRepository.markUsed(token.getId(), clock.instant()) != 1) {
            throw new InvalidActivationTokenException(INVALID_TOKEN_MESSAGE);
        }
        return token.getOrganizer();
    }

    private OrganizerToken findUsable(String rawToken, OrganizerTokenPurpose purpose) {
        return organizerTokenRepository.findByTokenHash(hash(rawToken == null ? "" : rawToken))
                .filter(t -> t.getPurpose() == purpose)
                .filter(t -> t.isUsable(clock.instant()))
                .orElseThrow(() -> new InvalidActivationTokenException(INVALID_TOKEN_MESSAGE));
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return RAW_TOKEN_ENCODER.encodeToString(bytes);
    }

    private static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible en esta JVM", e);
        }
    }
}
