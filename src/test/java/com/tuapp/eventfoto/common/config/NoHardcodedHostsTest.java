package com.tuapp.eventfoto.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Toda URL absoluta sale de AppUrls (APP_BASE_URL): mudar de dominio es cambiar una variable en Railway.
 * Falla si en src/main aparece un dominio propio escrito a mano o una URL armada desde el host de la request
 * (que además se puede falsificar con el header Host). AppUrls es el único archivo que puede nombrar el dominio.
 */
class NoHardcodedHostsTest {

    private static final Path MAIN = Path.of("src", "main");
    private static final Set<String> TEXT_EXTENSIONS = Set.of("java", "html", "js", "css", "yml", "yaml", "properties", "json", "xml", "txt");

    /**
     * Los mails @eventfoto.com.ar (superadmin por defecto) no son URLs: el lookbehind los deja pasar. Tampoco la mención
     * entre paréntesis del texto legal ("EventFoto (eventfoto.com.ar)"), que identifica al responsable y no es un link.
     */
    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("up\\.railway\\.app"),
            Pattern.compile("(?<![@(])eventfoto\\.com\\.ar"),
            Pattern.compile("getRequestURL"),
            Pattern.compile("ServletUriComponentsBuilder"),
            Pattern.compile("X-Forwarded-Host", Pattern.CASE_INSENSITIVE),
            Pattern.compile("location\\.origin"));

    @Test
    @DisplayName("Ningún archivo de src/main escribe un dominio propio ni deriva URLs del host de la request")
    void noHardcodedHosts() throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(Files::isRegularFile).filter(NoHardcodedHostsTest::isText).toList()) {
                if (file.getFileName().toString().equals("AppUrls.java")) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    for (Pattern pattern : FORBIDDEN) {
                        if (pattern.matcher(lines.get(i)).find()) {
                            hits.add(file + ":" + (i + 1) + " [" + pattern.pattern() + "] " + lines.get(i).trim());
                        }
                    }
                }
            }
        }
        assertThat(hits).as("Usar AppUrls en vez de escribir el host o leerlo de la request").isEmpty();
    }

    private static boolean isText(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 && TEXT_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase());
    }
}
