package com.tuapp.eventfoto.migration;

import com.tuapp.eventfoto.testsupport.PostgresTestCredentials;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Arranca la app COMPLETA (sin el perfil "test", que fuerza H2 + Flyway apagado +
 * ddl-auto=create-drop) contra un Postgres real, exactamente con la configuración de
 * producción de application.yml: Flyway corre todas las migraciones desde cero y
 * Hibernate valida (ddl-auto=validate) que cada entidad matchea el esquema resultante.
 *
 * Antes de este test, la primera vez que las migraciones corrían contra un Postgres de
 * verdad era en el deploy a producción -- un typo en una migración, una entidad
 * desincronizada del esquema real, o un tipo de columna que H2 acepta pero Postgres no,
 * recién se hubieran visto ahí. Ahora se detectan en CI.
 *
 * Solo corre cuando hay Postgres real disponible (service container de CI, o .env local
 * -- ver PostgresTestCredentials); si no, se salta la clase entera SIN intentar levantar
 * el contexto de Spring (@EnabledIfEnvironmentVariable evalúa antes de cualquier bootstrap,
 * a diferencia de un Assumptions.assumeTrue dentro de un @Test, que llegaría tarde).
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:postgresql://.+")
class SchemaValidationIntegrationTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        PostgresTestCredentials.Credentials creds = PostgresTestCredentials.resolveOrNull();
        if (creds == null) {
            return; // @EnabledIfEnvironmentVariable ya debería haber saltado la clase antes de llegar acá
        }
        registry.add("spring.datasource.url", creds::url);
        registry.add("spring.datasource.username", creds::user);
        registry.add("spring.datasource.password", creds::password);
    }

    @Test
    @DisplayName("Las migraciones de Flyway y las entidades JPA coinciden en un Postgres real (ddl-auto=validate)")
    void contextLoadsWithFlywayAndSchemaValidationAgainstRealPostgres() {
        // El arranque en sí ya migra con Flyway y valida el esquema con Hibernate; si hay
        // drift entre las entidades y las migraciones, el contexto no levanta y el test
        // falla en la carga, no acá.
    }
}
