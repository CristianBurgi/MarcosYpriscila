package com.tuapp.eventfoto.migration;

import com.tuapp.eventfoto.testsupport.PostgresTestCredentials;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * V12 (fase 9.1 Bloque 1) agrega events.organizer_id NOT NULL asumiendo que 'events'
 * está vacía -- el guard tiene que frenar el deploy con un error claro si alguna vez
 * no lo está, en vez de dejar la tabla en un estado inconsistente.
 *
 * Esto no se puede probar contra H2 (los tests normales de la app corren con Flyway
 * deshabilitado y ddl-auto=create-drop): el guard usa un bloque PL/pgSQL (DO $$ ...
 * RAISE EXCEPTION) que H2 no soporta. Se necesita un Postgres real -- en CI lo provee
 * el service container del workflow (ver .github/workflows/ci.yml, variables de entorno
 * reales DB_URL/DB_USER/DB_PASSWORD); en local cae al .env del repo (mismo mecanismo que
 * usa la app) -- ver PostgresTestCredentials.
 *
 * Si faltan credenciales o Postgres no responde: en CI (variable CI=true, que GitHub
 * Actions define siempre) el test FALLA con un mensaje claro -- ahí se supone que el
 * service container arranca, así que un fallo silencioso (test salteado, CI en verde
 * igual) sería peor que no tener el test. En local, sin esa garantía, se omite.
 *
 * Corre contra una base descartable creada y borrada en el mismo test -- nunca toca
 * eventfoto_db (la base real de desarrollo local) ni la del service container de CI.
 */
class FlywayEventGuardTest {

    private String adminUrl;
    private String user;
    private String password;
    private String throwawayDb;
    private String throwawayUrl;

    @BeforeEach
    void setUp() throws SQLException {
        PostgresTestCredentials.Credentials creds = PostgresTestCredentials.resolveOrNull();
        if (creds == null) {
            if (PostgresTestCredentials.isCi()) {
                fail("CI=true pero faltan DB_URL/DB_USER/DB_PASSWORD de Postgres: revisar el service container en .github/workflows/ci.yml. Este test no puede saltearse en CI.");
            }
            assumeTrue(false, "Sin DB_URL/DB_USER/DB_PASSWORD de Postgres (env real o .env): se omite (solo en local)");
        }

        this.adminUrl = creds.url();
        this.user = creds.user();
        this.password = creds.password();

        boolean reachable;
        try (Connection ignored = DriverManager.getConnection(adminUrl, user, password)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        if (!reachable) {
            if (PostgresTestCredentials.isCi()) {
                fail("CI=true pero Postgres no responde en " + redact(adminUrl) + ": revisar el health check del service container en .github/workflows/ci.yml.");
            }
            assumeTrue(false, "Postgres local no responde en " + redact(adminUrl) + ": se omite (solo en local)");
        }

        throwawayDb = "eventfoto_flyway_guard_" + System.nanoTime();
        try (Connection conn = DriverManager.getConnection(adminUrl, user, password);
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE " + throwawayDb);
        }
        throwawayUrl = replaceDbName(adminUrl, throwawayDb);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (throwawayDb == null) {
            return;
        }
        try (Connection conn = DriverManager.getConnection(adminUrl, user, password);
             Statement st = conn.createStatement()) {
            // Cierra conexiones colgadas de Flyway/Hikari antes del DROP.
            st.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + throwawayDb + "' AND pid <> pg_backend_pid()");
            st.execute("DROP DATABASE IF EXISTS " + throwawayDb);
        }
    }

    @Test
    @DisplayName("V12 falla con un mensaje claro si 'events' ya tiene filas al momento de correr")
    void v12FailsWhenEventsHasRows() throws SQLException {
        Flyway.configure()
                .dataSource(throwawayUrl, user, password)
                .target("11")
                .load()
                .migrate();

        try (Connection conn = DriverManager.getConnection(throwawayUrl, user, password);
             Statement st = conn.createStatement()) {
            st.execute("""
                    INSERT INTO events (id, name, slug, event_date, upload_deadline, is_active, created_at)
                    VALUES (gen_random_uuid(), 'Evento fantasma', 'evento-fantasma', now(), now() + interval '1 day', true, now())
                    """);
        } catch (SQLException e) {
            // gen_random_uuid() requiere pgcrypto en algunas instalaciones de Postgres.
            try (Connection conn = DriverManager.getConnection(throwawayUrl, user, password);
                 Statement st = conn.createStatement()) {
                st.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
                st.execute("""
                        INSERT INTO events (id, name, slug, event_date, upload_deadline, is_active, created_at)
                        VALUES (gen_random_uuid(), 'Evento fantasma', 'evento-fantasma', now(), now() + interval '1 day', true, now())
                        """);
            }
        }

        Flyway fullMigration = Flyway.configure()
                .dataSource(throwawayUrl, user, password)
                .load();

        assertThatThrownBy(fullMigration::migrate)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V12");

        try (Connection conn = DriverManager.getConnection(throwawayUrl, user, password);
             Statement st = conn.createStatement()) {
            var rs = st.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_name = 'events' AND column_name = 'organizer_id'");
            assertThat(rs.next()).as("organizer_id no debería existir: V12 tiene que abortar antes del ALTER TABLE").isFalse();
        }
    }

    private static String replaceDbName(String jdbcUrl, String newDbName) {
        Matcher m = Pattern.compile("(jdbc:postgresql://[^/]+/)([^?]+)(.*)").matcher(jdbcUrl);
        if (!m.matches()) {
            throw new IllegalArgumentException("No se pudo parsear DB_URL para reemplazar el nombre de la base: " + redact(jdbcUrl));
        }
        return m.group(1) + newDbName + m.group(3);
    }

    private static String redact(String jdbcUrl) {
        return jdbcUrl.replaceAll("://[^@/]+@", "://***@");
    }
}
