package com.tuapp.eventfoto.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSecretsValidatorTest {

    private static final String REAL_JWT = "k7Qp2vX9mR4tW8zL1nB6cF3hJ0dS5gY2aE7uI9oP";

    private static ProductionSecretsValidator validator(String mode, String superadminEmail, String superadminPassword,
                                                          String jwt, String db, String r2Key, String r2Secret) {
        return new ProductionSecretsValidator(mode, superadminEmail, superadminPassword, jwt, db, r2Key, r2Secret);
    }

    @Test
    @DisplayName("En producción (r2) no arranca si SUPERADMIN_PASSWORD o JWT_SECRET tienen el valor de ejemplo, y el mensaje no expone el valor")
    void productionWithExampleSecretsFailsWithoutLeakingValues() {
        var v = validator("r2", "real-admin@example.com", "admin123",
                "very_secret_jwt_key_that_is_at_least_256_bits_long_for_security_reasons",
                "real-db-pass-XYZ", "real-access", "real-secret");

        assertThatThrownBy(v::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPERADMIN_PASSWORD")
                .hasMessageContaining("JWT_SECRET")
                .hasMessageNotContaining("admin123")
                .hasMessageNotContaining("very_secret_jwt_key");
    }

    @Test
    @DisplayName("En producción detecta cada valor de ejemplo conocido, en cada variable")
    void everyKnownExampleValueIsRejected() {
        ProductionSecretsValidator.KNOWN_EXAMPLE_VALUES.forEach((var, examples) -> examples.forEach(example -> {
            var v = validator("r2",
                    var.equals("SUPERADMIN_EMAIL") ? example : "real-admin@example.com",
                    var.equals("SUPERADMIN_PASSWORD") ? example : "real-admin-pass",
                    var.equals("JWT_SECRET") ? example : REAL_JWT,
                    var.equals("DB_PASSWORD") ? example : "real-db-pass",
                    var.equals("R2_ACCESS_KEY") ? example : "real-access",
                    var.equals("R2_SECRET_KEY") ? example : "real-secret");
            assertThat(v.findProblems()).as(var + " = valor de ejemplo").singleElement().asString().startsWith(var);
        }));
    }

    @Test
    @DisplayName("En producción no arranca con secretos vacíos ni con un JWT_SECRET de menos de 32 caracteres")
    void missingOrShortSecretsFail() {
        assertThat(validator("r2", "real-admin@example.com", "", REAL_JWT, "db", "a", "s").findProblems())
                .containsExactly("SUPERADMIN_PASSWORD: no está seteada");
        assertThat(validator("r2", "", "real", REAL_JWT, "db", "a", "s").findProblems())
                .containsExactly("SUPERADMIN_EMAIL: no está seteada");
        assertThat(validator("r2", "real-admin@example.com", "real", "corto", "db", "a", "s").findProblems())
                .containsExactly("JWT_SECRET: tiene menos de 32 caracteres");
    }

    @Test
    @DisplayName("Con secretos reales arranca; en modo local no valida (los valores de ejemplo son lo esperado en desarrollo)")
    void realSecretsOrLocalModeStart() {
        assertThatCode(validator("r2", "real-admin@example.com", "real-admin-pass", REAL_JWT, "real-db", "real-access", "real-secret")::validateOnStartup)
                .doesNotThrowAnyException();
        assertThatCode(validator("local", "superadmin@eventfoto.com.ar", "admin123", "super_secret_jwt_key_minimo_32_caracteres_123456",
                "postgres_local_dev_password", "r2_placeholder_access_key", "r2_placeholder_secret_key")::validateOnStartup)
                .doesNotThrowAnyException();
    }

    /**
     * Red de seguridad: lee los archivos versionados del repo y exige que todo valor de
     * ejemplo de un secreto esté en la lista del validador. Si alguien agrega o cambia
     * un ejemplo en .env.example o en un default de application.yml, este test falla
     * hasta que se sume a KNOWN_EXAMPLE_VALUES.
     */
    @Test
    @DisplayName("Todo valor de ejemplo de un secreto publicado en el repo está en la lista del validador")
    void knownExamplesCoverEveryExampleInTheRepo() throws IOException {
        Map<String, List<String>> found = new java.util.HashMap<>();
        ProductionSecretsValidator.KNOWN_EXAMPLE_VALUES.keySet().forEach(var -> found.put(var, new ArrayList<>()));

        // 1. Defaults de application.yml: ${VAR:default}
        Matcher m = Pattern.compile("\\$\\{([A-Z0-9_]+):([^}]*)}").matcher(read("src/main/resources/application.yml"));
        while (m.find()) {
            if (found.containsKey(m.group(1))) found.get(m.group(1)).add(m.group(2).trim());
        }

        // 2. .env.example: VAR=valor
        for (String line : read(".env.example").split("\\R")) {
            int eq = line.indexOf('=');
            if (!line.startsWith("#") && eq > 0 && found.containsKey(line.substring(0, eq).trim())) {
                found.get(line.substring(0, eq).trim()).add(line.substring(eq + 1).trim());
            }
        }

        // 3. application-test.yml y docker-compose.yml (propiedades, no variables)
        Map<String, Object> testYml = new Yaml().load(read("src/test/resources/application-test.yml"));
        found.get("SUPERADMIN_EMAIL").add(String.valueOf(path(testYml, "security", "superadmin", "email")));
        found.get("SUPERADMIN_PASSWORD").add(String.valueOf(path(testYml, "security", "superadmin", "password")));
        found.get("JWT_SECRET").add(String.valueOf(path(testYml, "security", "jwt", "secret")));
        found.get("DB_PASSWORD").add(String.valueOf(path(testYml, "spring", "datasource", "password")));
        Map<String, Object> compose = new Yaml().load(read("docker-compose.yml"));
        found.get("DB_PASSWORD").add(String.valueOf(path(compose, "services", "postgres", "environment", "POSTGRES_PASSWORD")));

        found.forEach((var, examples) -> {
            assertThat(examples).as("se encontraron ejemplos de " + var + " en el repo").isNotEmpty();
            assertThat(ProductionSecretsValidator.KNOWN_EXAMPLE_VALUES.get(var))
                    .as("valores de ejemplo de " + var + " publicados en el repo")
                    .containsAll(examples.stream().filter(e -> !e.isEmpty()).toList());
        });
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Object path(Map<String, Object> root, String... keys) {
        Object current = root;
        for (String key : keys) {
            current = ((Map<String, Object>) current).get(key);
        }
        return current;
    }
}
