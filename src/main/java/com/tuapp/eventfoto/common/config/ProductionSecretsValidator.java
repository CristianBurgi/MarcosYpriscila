package com.tuapp.eventfoto.common.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Falla el arranque en producción ({@code app.storage.mode=r2}) si algún secreto coincide
 * con un valor de ejemplo publicado en el repo (defaults de application.yml, .env.example,
 * application-test.yml, docker-compose.yml). El repo es público: cualquiera que lea esos
 * archivos podría entrar al panel (ADMIN_PASSWORD) o firmar sus propios JWT de admin
 * (JWT_SECRET) si producción quedó con el valor de ejemplo.
 *
 * Fase 9.0 (A2): el 24/09 se detectó que APP_BASE_URL en Railway tenía un placeholder
 * ("tu-boda-produccion...") y nadie se enteró porque el código lo ignoraba en silencio.
 * Preferimos no arrancar antes que correr con configuración de ejemplo.
 *
 * El mensaje de error nombra la VARIABLE, nunca el valor. En desarrollo local (modo
 * 'local') no se valida: ahí usar los valores de ejemplo es lo esperado.
 *
 * Si agregás un valor de ejemplo nuevo en alguno de esos archivos, sumalo acá:
 * ProductionSecretsValidatorTest lee los archivos del repo y falla si falta alguno.
 */
@Slf4j
@Component
public class ProductionSecretsValidator {

    /** Variable de entorno -> valores de ejemplo publicados en el repo. */
    static final Map<String, Set<String>> KNOWN_EXAMPLE_VALUES = Map.of(
            "ADMIN_PASSWORD", Set.of("admin123"),
            "JWT_SECRET", Set.of(
                    "very_secret_jwt_key_that_is_at_least_256_bits_long_for_security_reasons",
                    "super_secret_jwt_key_minimo_32_caracteres_123456"),
            "DB_PASSWORD", Set.of("postgres_local_dev_password", "tu_contraseña_railway_postgres", "sa"),
            "R2_ACCESS_KEY", Set.of("r2_placeholder_access_key", "tu_r2_access_key_id"),
            "R2_SECRET_KEY", Set.of("r2_placeholder_secret_key", "tu_r2_secret_access_key")
    );

    private final String storageMode;
    private final Map<String, String> actualValues;

    public ProductionSecretsValidator(
            @Value("${app.storage.mode:local}") String storageMode,
            @Value("${security.admin.password:}") String adminPassword,
            @Value("${security.jwt.secret:}") String jwtSecret,
            @Value("${spring.datasource.password:}") String dbPassword,
            @Value("${cloudflare.r2.access-key:}") String r2AccessKey,
            @Value("${cloudflare.r2.secret-key:}") String r2SecretKey) {
        this.storageMode = storageMode;
        this.actualValues = Map.of(
                "ADMIN_PASSWORD", nullToEmpty(adminPassword),
                "JWT_SECRET", nullToEmpty(jwtSecret),
                "DB_PASSWORD", nullToEmpty(dbPassword),
                "R2_ACCESS_KEY", nullToEmpty(r2AccessKey),
                "R2_SECRET_KEY", nullToEmpty(r2SecretKey));
    }

    @PostConstruct
    void validateOnStartup() {
        if (!"r2".equalsIgnoreCase(storageMode == null ? "" : storageMode.trim())) {
            log.info("app.storage.mode='{}' (no es producción): se omite la validación de secretos de ejemplo.", storageMode);
            return;
        }

        List<String> problemas = findProblems();
        if (!problemas.isEmpty()) {
            throw new IllegalStateException("Producción (app.storage.mode=r2) con secretos inválidos:\n  - "
                    + String.join("\n  - ", problemas)
                    + "\n\nCargá valores reales en las variables de Railway. La aplicación no arranca con valores"
                    + " de ejemplo porque el repo es público.");
        }
        log.info("Secretos de producción validados: ninguno coincide con un valor de ejemplo del repo.");
    }

    List<String> findProblems() {
        List<String> problemas = new ArrayList<>();
        KNOWN_EXAMPLE_VALUES.keySet().stream().sorted().forEach(var -> {
            String value = actualValues.get(var).trim();
            if (value.isEmpty()) {
                problemas.add(var + ": no está seteada");
            } else if (KNOWN_EXAMPLE_VALUES.get(var).contains(value)) {
                problemas.add(var + ": tiene un valor de ejemplo publicado en el repo");
            }
        });
        String jwt = actualValues.get("JWT_SECRET").trim();
        if (!jwt.isEmpty() && jwt.length() < 32) {
            problemas.add("JWT_SECRET: tiene menos de 32 caracteres");
        }
        return problemas;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
