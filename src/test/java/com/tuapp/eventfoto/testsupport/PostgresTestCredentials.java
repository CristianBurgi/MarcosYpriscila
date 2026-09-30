package com.tuapp.eventfoto.testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Credenciales de un Postgres real para los tests que necesitan uno de verdad (Flyway
 * corriendo migraciones PL/pgSQL, validación de esquema con ddl-auto=validate -- H2 no
 * sirve para ninguna de las dos cosas).
 *
 * Prioridad: variables de entorno del sistema primero (así funciona en CI, donde
 * GitHub Actions las setea directo sin ningún .env) -- si faltan, cae al .env del repo
 * (conveniencia para desarrollo local, mismo mecanismo que usa la app misma vía
 * spring-dotenv). Nunca se loguean ni se exponen los valores resueltos.
 */
public final class PostgresTestCredentials {

    public record Credentials(String url, String user, String password) {
    }

    private PostgresTestCredentials() {
    }

    public static Credentials resolveOrNull() {
        String url = firstNonBlank(System.getenv("DB_URL"), () -> loadDotEnv().get("DB_URL"));
        String user = firstNonBlank(System.getenv("DB_USER"), () -> loadDotEnv().get("DB_USER"));
        String password = firstNonBlank(System.getenv("DB_PASSWORD"), () -> loadDotEnv().get("DB_PASSWORD"));

        if (url == null || user == null || password == null || !url.startsWith("jdbc:postgresql://")) {
            return null;
        }
        return new Credentials(url, user, password);
    }

    private static String firstNonBlank(String primary, java.util.function.Supplier<String> fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        String fromFallback = fallback.get();
        return (fromFallback != null && !fromFallback.isBlank()) ? fromFallback : null;
    }

    private static Map<String, String> loadDotEnv() {
        Path path = Path.of(".env");
        Map<String, String> map = new HashMap<>();
        if (!Files.exists(path)) {
            return map;
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq > 0) {
                    map.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
                }
            }
        } catch (IOException e) {
            // Sin .env legible: se sigue con lo que haya en variables de entorno reales.
        }
        return map;
    }
}
