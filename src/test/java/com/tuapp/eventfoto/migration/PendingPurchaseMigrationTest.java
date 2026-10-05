package com.tuapp.eventfoto.migration;

import com.tuapp.eventfoto.testsupport.PostgresTestCredentials;
import org.flywaydb.core.Flyway;
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
 * V15 (fase 9.3): pending_purchase. El CHECK "o organizer_id, o email + password_hash, nunca ambos ni ninguno" vive en
 * la base y por eso se prueba contra Postgres real (H2 de los otros tests se arma desde las entidades, sin CHECK).
 * En CI lo provee el service container; en local se omite si no hay uno; en CI sin Postgres FALLA.
 * V16 (fase 9.4): el modo XOR aplica solo sin procesar; procesada, sin datos personales y con el pago asociado.
 */
class PendingPurchaseMigrationTest {

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

        throwawayDb = "eventfoto_v15_" + System.nanoTime();
        try (Connection conn = DriverManager.getConnection(adminUrl, user, password); Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE " + throwawayDb);
        }
        Matcher m = Pattern.compile("(jdbc:postgresql://[^/]+/)([^?]+)(.*)").matcher(adminUrl);
        if (!m.matches()) {
            throw new IllegalArgumentException("No se pudo parsear DB_URL");
        }
        throwawayUrl = m.group(1) + throwawayDb + m.group(3);
    }

    private void migrate(String target) {
        var config = Flyway.configure().dataSource(throwawayUrl, user, password);
        (target == null ? config : config.target(target)).load().migrate();
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

    private static final String INSERT = "INSERT INTO pending_purchase (id, organizer_id, email, password_hash, event_name, amount, mp_payment_id) VALUES (gen_random_uuid(), %s, %s, %s, 'Evento', %s, %s)";

    @Test
    @DisplayName("CHECK: cliente existente o cliente nuevo sí; ambos modos, ninguno, o un modo a medias, fallan")
    void checkConstraintAcceptsExactlyOneMode() throws SQLException {
        migrate(null);
        try (Connection conn = DriverManager.getConnection(throwawayUrl, user, password); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO organizer (id, email) VALUES ('11111111-1111-4111-8111-111111111111', 'existente@test.com')");
            String org = "'11111111-1111-4111-8111-111111111111'";

            st.execute(String.format(INSERT, org, "NULL", "NULL", "50000", "NULL"));                       // cliente existente
            st.execute(String.format(INSERT, "NULL", "'nuevo@test.com'", "'$2a$hash'", "50000", "NULL"));  // cliente nuevo

            assertThatThrownBy(() -> st.execute(String.format(INSERT, org, "'x@test.com'", "'$2a$hash'", "50000", "NULL")))
                    .as("ambos modos").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute(String.format(INSERT, "NULL", "NULL", "NULL", "50000", "NULL")))
                    .as("ninguno").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute(String.format(INSERT, "NULL", "'solo-email@test.com'", "NULL", "50000", "NULL")))
                    .as("email sin hash").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute(String.format(INSERT, "NULL", "NULL", "'$2a$hash'", "50000", "NULL")))
                    .as("hash sin email").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute(String.format(INSERT, org, "NULL", "'$2a$hash'", "50000", "NULL")))
                    .as("organizer con hash").isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("El monto tiene que ser > 0, el organizer_id tiene que existir y un mp_payment_id no se repite (UNIQUE parcial)")
    void amountForeignKeyAndPaymentIdUniqueness() throws SQLException {
        migrate(null);
        try (Connection conn = DriverManager.getConnection(throwawayUrl, user, password); Statement st = conn.createStatement()) {
            assertThatThrownBy(() -> st.execute(String.format(INSERT, "NULL", "'a@test.com'", "'$2a$hash'", "0", "NULL")))
                    .as("monto 0").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute(String.format(INSERT, "'22222222-2222-4222-8222-222222222222'", "NULL", "NULL", "50000", "NULL")))
                    .as("organizer inexistente").isInstanceOf(SQLException.class);

            st.execute(String.format(INSERT, "NULL", "'p1@test.com'", "'$2a$hash'", "50000", "'777'"));
            assertThatThrownBy(() -> st.execute(String.format(INSERT, "NULL", "'p2@test.com'", "'$2a$hash'", "50000", "'777'")))
                    .as("mismo pago de MP en dos compras").isInstanceOf(SQLException.class);
            // Varias compras sin pago asociado son lo normal
            st.execute(String.format(INSERT, "NULL", "'p3@test.com'", "'$2a$hash'", "50000", "NULL"));
            st.execute(String.format(INSERT, "NULL", "'p4@test.com'", "'$2a$hash'", "50000", "NULL"));
        }
    }

    private static final String UPDATE_PROCESSED = "UPDATE pending_purchase SET processed_at = now(), %s WHERE id = '%s'";

    @Test
    @DisplayName("V16 sobre filas existentes de V15: procesada solo con organizer_id + mp_payment_id y SIN email ni hash; sin procesar, el XOR de siempre")
    void v16ProcessedRowsCarryNoPersonalData() throws SQLException {
        migrate("15");
        String org = "'33333333-3333-4333-8333-333333333333'";
        String nueva = "44444444-4444-4444-8444-444444444444";
        String recompra = "55555555-5555-4555-8555-555555555555";
        try (Connection conn = DriverManager.getConnection(throwawayUrl, user, password); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO organizer (id, email) VALUES (" + org + ", 'existente@test.com')");
            st.execute("INSERT INTO pending_purchase (id, email, password_hash, event_name, amount) VALUES ('" + nueva + "', 'nueva@test.com', '$2a$hash', 'E', 50000)");
            st.execute("INSERT INTO pending_purchase (id, organizer_id, event_name, amount) VALUES ('" + recompra + "', " + org + ", 'E', 50000)");
        }

        var result = Flyway.configure().dataSource(throwawayUrl, user, password).load().migrate();
        assertThat(result.migrationsExecuted).as("V16 corre sobre las filas existentes").isGreaterThanOrEqualTo(1);

        try (Connection conn = DriverManager.getConnection(throwawayUrl, user, password); Statement st = conn.createStatement()) {
            // Procesada sin borrar email/hash, sin pago o sin organizer: no
            assertThatThrownBy(() -> st.execute(String.format(UPDATE_PROCESSED, "mp_payment_id = '1', organizer_id = " + org, nueva)))
                    .as("procesada con email y hash").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute(String.format(UPDATE_PROCESSED, "organizer_id = " + org + ", email = NULL, password_hash = NULL", nueva)))
                    .as("procesada sin mp_payment_id").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute(String.format(UPDATE_PROCESSED, "mp_payment_id = '1', email = NULL, password_hash = NULL", nueva)))
                    .as("procesada sin organizer_id").isInstanceOf(SQLException.class);

            // Incidente de "email ya registrado": sin procesar, con email + hash y con el pago guardado: sí
            st.execute("UPDATE pending_purchase SET mp_payment_id = '2' WHERE id = '" + nueva + "'");
            // Procesada como corresponde (compra nueva minimizada / recompra): sí
            st.execute(String.format(UPDATE_PROCESSED, "mp_payment_id = '3', organizer_id = " + org + ", email = NULL, password_hash = NULL, created_organizer = true", nueva));
            st.execute(String.format(UPDATE_PROCESSED, "mp_payment_id = '4', created_organizer = false", recompra));

            // Sin procesar sigue valiendo el XOR
            assertThatThrownBy(() -> st.execute("INSERT INTO pending_purchase (id, event_name, amount) VALUES (gen_random_uuid(), 'E', 50000)"))
                    .as("ningún modo").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("INSERT INTO pending_purchase (id, organizer_id, email, password_hash, event_name, amount) VALUES (gen_random_uuid(), " + org + ", 'x@test.com', 'h', 'E', 50000)"))
                    .as("ambos modos").isInstanceOf(SQLException.class);

            // payment_incident: uno por payment_id, con resolved_at para la 9.7
            st.execute("INSERT INTO payment_incident (id, payment_id, external_reference, reason) VALUES (gen_random_uuid(), '99', 'ref', 'UNKNOWN_REFERENCE')");
            assertThatThrownBy(() -> st.execute("INSERT INTO payment_incident (id, payment_id, external_reference, reason) VALUES (gen_random_uuid(), '99', 'ref', 'AMOUNT_MISMATCH')"))
                    .isInstanceOf(SQLException.class);
            st.execute("UPDATE payment_incident SET resolved_at = now() WHERE payment_id = '99'");
        }
    }
}
