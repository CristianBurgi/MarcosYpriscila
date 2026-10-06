package com.tuapp.eventfoto.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Paleta: EventPalette.java (la fuente de verdad) contra los valores esperados del brief (calculados con el JS
 * del mockup) y contra static/js/event-palette.js (la vista previa del wizard) corriendo de verdad en Node.
 * Node viene en los runners de GitHub; en local se omite si no está instalado, en CI sin Node FALLA.
 */
class EventPaletteParityTest {

    /** color, primaryDeep, primaryLight, secondary, textOnPrimary, textOnSecondary */
    private static final List<List<String>> EXPECTED = List.of(
            List.of("#2e4374", "#11192b", "#e8e9ee", "#596a90", "#FFFFFF", "#FFFFFF"),
            List.of("#c89b3c", "#7b5f23", "#efece7", "#c1b08a", "#FFFFFF", "#1C2321"),
            List.of("#f5f5f0", "#ccccb3", "#edede9", "#c9c9b6", "#1C2321", "#1C2321"),
            List.of("#e91e63", "#930e3b", "#f0e5e9", "#cf809a", "#FFFFFF", "#1C2321"),
            List.of("#7fffd4", "#19ffb2", "#e3f2ed", "#9ce2cb", "#1C2321", "#1C2321"),
            // Arreglos de la 9.5: gris sin tinte rosado (el mockup daba #ede9e9 / #b19696) ...
            List.of("#808080", "#4d4d4d", "#ebebeb", "#a4a4a4", "#FFFFFF", "#1C2321"),
            // ... y el negro: primaryDeep ya no queda más claro que el primario (el mockup daba #1a1a1a).
            List.of("#111111", "#111111", "#ebebeb", "#404040", "#FFFFFF", "#FFFFFF"));

    @Test
    @DisplayName("Java: cada color da la paleta esperada (tabla del brief + #808080 y #111111)")
    void javaMatchesTheExpectedTable() {
        for (List<String> row : EXPECTED) {
            assertThat(asRow(EventPalette.derive(row.get(0)))).as(row.get(0)).isEqualTo(row);
        }
    }

    @Test
    @DisplayName("JS de la vista previa == Java, color por color (Node)")
    void javascriptMatchesJava() throws Exception {
        String script = """
                const [modulePath, colorsJson] = process.argv.slice(-2);
                const { derivePalette } = require(modulePath);
                const colors = colorsJson.split(',');
                console.log(JSON.stringify(colors.map(derivePalette)));
                """;
        List<String> colors = EXPECTED.stream().map(row -> row.get(0)).toList();
        JsonNode results = runNode(script, Path.of("src/main/resources/static/js/event-palette.js").toAbsolutePath().toString(),
                String.join(",", colors)); // sin comillas: Windows las come en los argumentos
        for (int i = 0; i < colors.size(); i++) {
            JsonNode js = results.get(i);
            List<String> jsRow = List.of(js.get("primary").asText(), js.get("primaryDeep").asText(), js.get("primaryLight").asText(),
                    js.get("secondary").asText(), js.get("textOnPrimary").asText(), js.get("textOnSecondary").asText());
            assertThat(jsRow).as("JS " + colors.get(i)).isEqualTo(asRow(EventPalette.derive(colors.get(i))));
        }
    }

    @Test
    @DisplayName("Solo #rrggbb; se normaliza a minúsculas")
    void onlyStrictHexIsAccepted() {
        assertThat(EventPalette.normalize("#2E4374")).isEqualTo("#2e4374");
        for (String bad : new String[]{"2e4374", "#2e437", "#2e43745", "#gggggg", "#2e4374;", " #2e4374", "red", ""}) {
            assertThatThrownBy(() -> EventPalette.normalize(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static List<String> asRow(EventPalette p) {
        return List.of(p.primary(), p.primaryDeep(), p.primaryLight(), p.secondary(), p.textOnPrimary(), p.textOnSecondary());
    }

    private static JsonNode runNode(String script, String... args) throws Exception {
        Process process;
        try {
            List<String> command = new java.util.ArrayList<>(List.of("node", "-e", script, "--"));
            command.addAll(List.of(args));
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            if (System.getenv("CI") != null) {
                fail("Node no está disponible en CI: " + e.getMessage());
            }
            assumeTrue(false, "Node no está instalado: se omite la paridad con el JS");
            return null;
        }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Node no terminó en 30 s");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as("salida de Node: " + output).isZero();
        return new ObjectMapper().readTree(output);
    }
}
