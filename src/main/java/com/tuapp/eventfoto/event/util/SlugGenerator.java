package com.tuapp.eventfoto.event.util;

import com.tuapp.eventfoto.event.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * El slug es la llave de acceso al álbum de un evento (URL pública, sin login, que
 * ven todos los invitados) -- no puede ser adivinable a partir del nombre solo, por
 * eso siempre lleva un sufijo random de alta entropía además del nombre normalizado.
 */
@Component
@RequiredArgsConstructor
public class SlugGenerator {

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_HYPHENS = Pattern.compile("^-+|-+$");

    /** Sin 0/O/1/l/I ni otros caracteres que se confunden a simple vista. */
    private static final String SUFFIX_ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz";
    private static final int SUFFIX_LENGTH = 8;
    private static final int MAX_ATTEMPTS = 10;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final EventRepository eventRepository;

    /**
     * Genera un slug único a partir del nombre del evento. Reintenta con un sufijo
     * nuevo si hay colisión (la unicidad real la garantiza el índice único de BD;
     * esto es solo para no fallar la primera vez que, por azar, se repite el sufijo).
     */
    public String generate(String eventName) {
        String base = normalize(eventName);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = base + "-" + randomSuffix();
            if (!eventRepository.existsBySlug(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No se pudo generar un slug único para '" + eventName + "' tras " + MAX_ATTEMPTS + " intentos");
    }

    static String normalize(String eventName) {
        String withoutAccents = Normalizer.normalize(eventName == null ? "" : eventName, Normalizer.Form.NFD);
        withoutAccents = DIACRITICS.matcher(withoutAccents).replaceAll("");
        String slugified = NON_ALPHANUMERIC.matcher(withoutAccents.toLowerCase()).replaceAll("-");
        slugified = EDGE_HYPHENS.matcher(slugified).replaceAll("");
        return slugified.isBlank() ? "evento" : slugified;
    }

    static String randomSuffix() {
        StringBuilder sb = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            sb.append(SUFFIX_ALPHABET.charAt(RANDOM.nextInt(SUFFIX_ALPHABET.length())));
        }
        return sb.toString();
    }
}
