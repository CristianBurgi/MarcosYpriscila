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
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Emite y valida tokens de un solo uso del organizador (activación de cuenta en este
 * bloque; reseteo de contraseña se suma en la 9.2 con el mismo mecanismo). El valor
 * en claro solo existe acá, en memoria, el instante en que se genera -- lo que se
 * persiste es su hash SHA-256.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizerTokenService {

    /**
     * Fase 9.1 Bloque 1: el superadmin le manda el link por WhatsApp a mano (los
     * mails llegan en la 9.2), así que tiene que dar tiempo real a que la persona lo
     * vea y lo abra sin ser tan largo como para quedar dando vueltas indefinidamente.
     */
    static final Duration ACTIVATION_TTL = Duration.ofDays(7);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder RAW_TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final OrganizerTokenRepository organizerTokenRepository;

    public record IssuedToken(String rawToken, Instant expiresAt) {
    }

    /**
     * Genera un token nuevo e invalida (vía {@code used_at}) cualquier token previo sin
     * usar del mismo organizador y propósito -- "generar uno nuevo invalida el anterior".
     */
    @Transactional
    public IssuedToken issue(Organizer organizer, OrganizerTokenPurpose purpose) {
        List<OrganizerToken> previous = organizerTokenRepository
                .findByOrganizerIdAndPurposeAndUsedAtIsNull(organizer.getId(), purpose);
        Instant now = Instant.now();
        previous.forEach(token -> token.setUsedAt(now));
        organizerTokenRepository.saveAll(previous);

        String rawToken = generateRawToken();
        Instant expiresAt = now.plus(ACTIVATION_TTL);

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
     * Valida y consume un token: si no existe, ya se usó, venció o el propósito no
     * coincide, rechaza con el mismo mensaje genérico en los tres casos.
     */
    @Transactional
    public Organizer consume(String rawToken, OrganizerTokenPurpose purpose) {
        OrganizerToken token = organizerTokenRepository.findByTokenHash(hash(rawToken))
                .filter(t -> t.getPurpose() == purpose)
                .filter(t -> t.isUsable(Instant.now()))
                .orElseThrow(() -> new InvalidActivationTokenException("El link no es válido, ya se usó o venció."));

        token.setUsedAt(Instant.now());
        organizerTokenRepository.save(token);
        return token.getOrganizer();
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
