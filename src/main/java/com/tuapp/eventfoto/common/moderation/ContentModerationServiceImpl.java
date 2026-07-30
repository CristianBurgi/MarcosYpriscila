package com.tuapp.eventfoto.common.moderation;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Set;

/**
 * Servicio de moderación de contenido para mensajes y comentarios.
 *
 * TODO: Integración futura con API de Moderación basada en IA (ej: Google Cloud Perspective API)
 */
@Slf4j
@Service
public class ContentModerationServiceImpl implements ContentModerationService {

    private final Set<String> blockedWords = new HashSet<>();

    @PostConstruct
    public void init() {
        loadBlockedWords();
    }

    private void loadBlockedWords() {
        try {
            ClassPathResource resource = new ClassPathResource("moderation/blocked-words-es.txt");
            if (!resource.exists()) {
                log.warn("Archivo de palabras bloqueadas 'moderation/blocked-words-es.txt' no encontrado.");
                return;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        String normalizedWord = normalizeText(trimmed);
                        if (!normalizedWord.isEmpty()) {
                            blockedWords.add(normalizedWord);
                        }
                    }
                }
            }
            log.info("Diccionario de moderación cargado exitosamente con {} palabras bloqueadas.", blockedWords.size());
        } catch (Exception e) {
            log.error("Error al cargar diccionario de moderación de contenido: {}", e.getMessage(), e);
        }
    }

    @Override
    public boolean isAllowed(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }

        String normalizedInput = normalizeText(text);

        for (String blocked : blockedWords) {
            if (normalizedInput.contains(blocked)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Normaliza el texto de entrada:
     * 1. Convierte a minúsculas.
     * 2. Elimina acentos/diacríticos (NFD).
     * 3. Remueve caracteres no alfanuméricos (puntos, guiones, símbolos).
     * 4. Colapsa caracteres idénticos repetidos 3 o más veces seguidas (ej: 'hdpppp' -> 'hdp').
     */
    public String normalizeText(String text) {
        if (text == null) return "";

        // 1. Minúsculas
        String lower = text.toLowerCase().trim();

        // 2. Remover acentos/diacríticos
        String nfdNormalized = Normalizer.normalize(lower, Normalizer.Form.NFD);
        String withoutAccents = nfdNormalized.replaceAll("\\p{M}", "");

        // 3. Remover caracteres no alfanuméricos
        String alphanumericOnly = withoutAccents.replaceAll("[^a-z0-9]", "");

        // 4. Colapsar repeticiones de 3 o más caracteres seguidos a uno solo
        return alphanumericOnly.replaceAll("(.)\\1{2,}", "$1");
    }
}
