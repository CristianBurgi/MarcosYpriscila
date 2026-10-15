package com.tuapp.eventfoto.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regla de la landing (Fase L6): no prometer nada que el producto no haga. Cada texto prohibido fue una promesa falsa
 * de la landing vieja: fotos ilimitadas (son 24 por invitado salvo que el organizador lo cambie), moderación
 * automática de imágenes (no existe), prueba gratis de 25 fotos (no existe; hay demo), el dominio equivocado y
 * fotos de stock de terceros. Sin distinguir mayúsculas ni tildes.
 */
class LandingPromisesTest {

    private static final Path LANDING = Path.of("src", "main", "resources", "templates", "landing.html");
    private static final List<String> FORBIDDEN = List.of(
            "ilimitad", "moderación automática", "prueba gratis", "25 fotos", "eventfoto.ar", "loremflickr");

    @Test
    @DisplayName("La landing no contiene ninguna promesa que el producto no cumple")
    void noFalsePromises() throws IOException {
        String landing = normalize(Files.readString(LANDING, StandardCharsets.UTF_8));
        assertThat(FORBIDDEN).as("Textos prohibidos en " + LANDING)
                .filteredOn(text -> landing.contains(normalize(text)))
                .isEmpty();
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
