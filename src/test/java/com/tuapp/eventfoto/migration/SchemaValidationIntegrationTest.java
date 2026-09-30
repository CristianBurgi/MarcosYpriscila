package com.tuapp.eventfoto.migration;

import com.tuapp.eventfoto.testsupport.PostgresTestCredentials;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
 * Si faltan credenciales (ver PostgresTestCredentials): en CI (CI=true, que GitHub
 * Actions define siempre) {@link #requirePostgresInCi()} FALLA la clase entera con un
 * mensaje claro, sin intentar levantar el contexto de Spring -- un service container
 * caído o una variable mal escrita no puede dejar este test salteado con CI en verde
 * igual. En local se omite. @BeforeAll (estático) corre ANTES de que SpringExtension
 * cree la instancia de test y dispare el bootstrap del contexto, así que el gate llega
 * a tiempo (a diferencia de un assumeTrue dentro de un @Test, que llegaría tarde).
 *
 * Si hay credenciales pero Postgres no responde (container caído, por ejemplo), no hace
 * falta un chequeo aparte acá: Spring intenta conectar al armar el pool de Hikari y esa
 * conexión fallida ya hace fallar el test con un mensaje claro, no lo saltea.
 *
 * <p>El contexto arranca SIN el perfil "test", así que además de Postgres necesita todo
 * lo que necesitaría producción para bootear -- en particular, S3Config arma el bean
 * S3Client/S3Presigner siempre (no solo con storage.mode=r2), parseando
 * cloudflare.r2.endpoint como URI. El default de application.yml para esa propiedad es
 * un placeholder literal ("https://&lt;account-id&gt;...") que no es una URI válida y
 * rompe ese bean. En local esto no se nota porque spring-dotenv carga el .env del repo
 * (que sí tiene un endpoint válido); en CI no hay .env, así que hace falta fijar acá un
 * valor de prueba válido -- sin tocar los defaults de application.yml, que
 * ProductionSecretsValidator/R2ConfigurationValidator necesitan intactos para poder
 * detectar placeholders reales en producción.
 */
@SpringBootTest
class SchemaValidationIntegrationTest {

    @BeforeAll
    static void requirePostgresInCi() {
        if (PostgresTestCredentials.resolveOrNull() != null) {
            return;
        }
        if (PostgresTestCredentials.isCi()) {
            fail("CI=true pero faltan DB_URL/DB_USER/DB_PASSWORD de Postgres: revisar el service container en .github/workflows/ci.yml. Este test no puede saltearse en CI.");
        }
        assumeTrue(false, "Sin DB_URL/DB_USER/DB_PASSWORD de Postgres (env real o .env): se omite (solo en local)");
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        PostgresTestCredentials.Credentials creds = PostgresTestCredentials.resolveOrNull();
        if (creds == null) {
            return; // requirePostgresInCi() ya frenó (fail o skip) antes de llegar acá
        }
        registry.add("spring.datasource.url", creds::url);
        registry.add("spring.datasource.username", creds::user);
        registry.add("spring.datasource.password", creds::password);

        // S3Config arma S3Client/S3Presigner siempre, parseando cloudflare.r2.endpoint
        // como URI -- el placeholder por defecto de application.yml no es una URI válida
        // (ver comentario de la clase). No es un endpoint real: nunca se llama en este
        // test (storage.mode sigue en "local"), solo tiene que ser sintácticamente válido
        // para que el bean se construya.
        registry.add("cloudflare.r2.endpoint", () -> "https://ci-test.r2.cloudflarestorage.com");
    }

    @Test
    @DisplayName("Las migraciones de Flyway y las entidades JPA coinciden en un Postgres real (ddl-auto=validate)")
    void contextLoadsWithFlywayAndSchemaValidationAgainstRealPostgres() {
        // El arranque en sí ya migra con Flyway y valida el esquema con Hibernate; si hay
        // drift entre las entidades y las migraciones, el contexto no levanta y el test
        // falla en la carga, no acá.
    }
}
