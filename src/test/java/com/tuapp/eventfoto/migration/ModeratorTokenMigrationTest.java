package com.tuapp.eventfoto.migration;

import com.tuapp.eventfoto.testsupport.PostgresTestCredentials;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * V13 (fase 9.2 Bloque A) agrega events.moderator_token y lo RELLENA para los eventos que ya existen. Para probar el
 * backfill de verdad: Flyway migra hasta V12 (con events vacía, que es lo que V12 exige), se insertan eventos y recién
 * entonces se aplica V13. Necesita un Postgres real (gen_random_uuid, encode/decode, ALTER ... SET NOT NULL): en CI lo
 * provee el service container postgres:18 del workflow; en local se omite si no hay uno. En CI, sin Postgres, FALLA.
 */
class ModeratorTokenMigrationTest {

    private static final Pattern TOKEN_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{43}$");

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
            assumeTrue(false, "Sin credenciales de Postgres (env real o .env): se omite (solo en local)");
        }
        adminUrl = creds.url();
        user = creds.user();
        password = creds.password();

        try (Connection ignored = DriverManager.getConnection(adminUrl, user, password)) {
            // alcanzable
        } catch (SQLException e) {
            if (PostgresTestCredentials.isCi()) {
                fail("CI=true pero Postgres no responde: revisar el health check del service container en .github/workflows/ci.yml.");
            }
            assumeTrue(false, "Postgres local no responde: se omite (solo en local)");
        }

        throwawayDb = "eventfoto_v13_" + System.nanoTime();
        try (Connection conn = DriverManager.getConnection(adminUrl, user, password); Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE " + throwawayDb);
        }
        Matcher m = Pattern.compile("(jdbc:postgresql://[^/]+/)([^?]+)(.*)").matcher(adminUrl);
        if (!m.matches()) {
            throw new IllegalArgumentException("No se pudo parsear DB_URL");
        }
        throwawayUrl = m.group(1) + throwawayDb + m.group(3);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (throwawayDb == null) {
            return;
        }
        try (Connection conn = DriverManager.getConnection(adminUrl, user, password); Statement st = conn.createStatement()) {
            st.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + throwawayDb + "' AND pid <> pg_backend_pid()");
            st.execute("DROP DATABASE IF EXISTS " + throwawayDb);
        }
    }

    @Test
    @DisplayName("V13 rellena un token distinto y bien formado (^[A-Za-z0-9_-]{43}$) en cada evento existente, y deja la columna NOT NULL con índice único")
    void backfillsEveryExistingEventWithAUniqueToken() throws SQLException {
        Flyway.configure().dataSource(throwawayUrl, user, password).target("12").load().migrate();

        int events = 40;
        try (Connection conn = DriverManager.getConnection(throwawayUrl, user, password); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO organizer (id, email) VALUES (gen_random_uuid(), 'backfill@test.com')");
            for (int i = 0; i < events; i++) {
                st.execute("INSERT INTO events (id, name, slug, event_date, upload_deadline, is_active, created_at, organizer_id, origin) "
                        + "SELECT gen_random_uuid(), 'Evento " + i + "', 'evento-" + i + "', current_date, now() + interval '1 day', true, now(), id, 'PAID' FROM organizer");
            }
            ResultSet before = st.executeQuery("SELECT count(*) FROM events");
            before.next();
            assertThat(before.getInt(1)).isEqualTo(events);
            ResultSet noColumnYet = st.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_name = 'events' AND column_name = 'moderator_token'");
            noColumnYet.next();
            assertThat(noColumnYet.getInt(1)).as("la columna no existe antes de V13").isZero();
        }

        var result = Flyway.configure().dataSource(throwawayUrl, user, password).load().migrate();
        assertThat(result.migrationsExecuted).as("solo V13 estaba pendiente").isEqualTo(1);

        try (Connection conn = DriverManager.getConnection(throwawayUrl, user, password); Statement st = conn.createStatement()) {
            Set<String> tokens = new HashSet<>();
            ResultSet rs = st.executeQuery("SELECT moderator_token FROM events");
            while (rs.next()) {
                String token = rs.getString(1);
                assertThat(token).as("token del backfill").isNotNull().matches(TOKEN_FORMAT);
                tokens.add(token);
            }
            assertThat(tokens).as("un token distinto por evento").hasSize(events);

            ResultSet nullable = st.executeQuery("SELECT is_nullable FROM information_schema.columns WHERE table_name = 'events' AND column_name = 'moderator_token'");
            nullable.next();
            assertThat(nullable.getString(1)).isEqualTo("NO");

            ResultSet index = st.executeQuery("SELECT indexdef FROM pg_indexes WHERE tablename = 'events' AND indexname = 'idx_events_moderator_token'");
            assertThat(index.next()).isTrue();
            assertThat(index.getString(1)).containsIgnoringCase("UNIQUE");

            String existing = tokens.iterator().next();
            assertThatThrownBy(() -> st.execute("INSERT INTO events (id, name, slug, event_date, upload_deadline, is_active, created_at, organizer_id, origin, moderator_token) "
                    + "SELECT gen_random_uuid(), 'Duplicado', 'duplicado', current_date, now(), true, now(), id, 'PAID', '" + existing + "' FROM organizer"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("V13 sobre una base sin eventos (instalación nueva) también migra")
    void migratesAnEmptyDatabase() throws SQLException {
        var result = Flyway.configure().dataSource(throwawayUrl, user, password).load().migrate();
        assertThat(result.success).isTrue();
        try (Connection conn = DriverManager.getConnection(throwawayUrl, user, password); Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT count(*) FROM events");
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }
}
