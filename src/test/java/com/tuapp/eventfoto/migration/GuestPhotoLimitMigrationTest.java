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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * V14 (fase 9.5) agrega events.max_photos_per_guest y rellena con 24 los eventos que ya existen. Se migra hasta
 * V13, se insertan eventos y recién entonces se aplica V14. Necesita un Postgres real (en CI lo provee el service
 * container; en local se omite si no hay uno; en CI sin Postgres FALLA), igual que ModeratorTokenMigrationTest.
 */
class GuestPhotoLimitMigrationTest {

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

        throwawayDb = "eventfoto_v14_" + System.nanoTime();
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
    @DisplayName("V14 rellena con 24 todos los eventos existentes; la columna admite NULL (sin límite), no admite <= 0 y no tiene DEFAULT")
    void backfillsExistingEventsWith24() throws SQLException {
        Flyway.configure().dataSource(throwawayUrl, user, password).target("13").load().migrate();

        int events = 25;
        try (Connection conn = DriverManager.getConnection(throwawayUrl, user, password); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO organizer (id, email) VALUES (gen_random_uuid(), 'backfill@test.com')");
            for (int i = 0; i < events; i++) {
                st.execute("INSERT INTO events (id, name, slug, event_date, upload_deadline, is_active, created_at, organizer_id, origin, moderator_token) "
                        + "SELECT gen_random_uuid(), 'Evento " + i + "', 'evento-" + i + "', current_date, now() + interval '1 day', true, now(), id, 'PAID', "
                        + "substr(md5(random()::text) || md5(random()::text), 1, 43) FROM organizer");
            }
            ResultSet noColumnYet = st.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_name = 'events' AND column_name = 'max_photos_per_guest'");
            noColumnYet.next();
            assertThat(noColumnYet.getInt(1)).as("la columna no existe antes de V14").isZero();
        }

        var result = Flyway.configure().dataSource(throwawayUrl, user, password).load().migrate();
        assertThat(result.migrationsExecuted).as("solo V14 estaba pendiente").isEqualTo(1);

        try (Connection conn = DriverManager.getConnection(throwawayUrl, user, password); Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT count(*), count(*) FILTER (WHERE max_photos_per_guest = 24) FROM events");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(events);
            assertThat(rs.getInt(2)).as("todos los eventos existentes quedan en 24").isEqualTo(events);

            ResultSet col = st.executeQuery("SELECT is_nullable, column_default FROM information_schema.columns WHERE table_name = 'events' AND column_name = 'max_photos_per_guest'");
            col.next();
            assertThat(col.getString(1)).as("admite NULL = sin límite").isEqualTo("YES");
            assertThat(col.getString(2)).as("sin DEFAULT de base: el default lo pone el código").isNull();

            String insert = "INSERT INTO events (id, name, slug, event_date, upload_deadline, is_active, created_at, organizer_id, origin, moderator_token, max_photos_per_guest) "
                    + "SELECT gen_random_uuid(), 'X', '%s', current_date, now(), true, now(), id, 'PAID', '%s', %s FROM organizer";
            st.execute(String.format(insert, "sin-limite", "t".repeat(43), "NULL"));
            assertThatThrownBy(() -> st.execute(String.format(insert, "cero", "u".repeat(43), "0"))).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute(String.format(insert, "negativo", "v".repeat(43), "-3"))).isInstanceOf(SQLException.class);
        }
    }
}
