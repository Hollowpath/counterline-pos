package com.counterline.pos.staff;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V35 on a database that already has corrections: they keep their name and are marked LEGACY (no guessed identity),
 * uncorrected events are untouched; the constraints keep the identity consistent; the audit log is append-only; and the
 * audit queries use the new indexes. A separate PostgreSQL, migrated by hand.
 */
class ActorAttributionMigrationTest {

    private static PostgreSQLContainer postgres;
    private static Connection db;

    @BeforeAll
    static void start() throws Exception {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
        postgres.start();
        flyway("34").migrate();
        db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        db.setAutoCommit(true);
        execute("INSERT INTO staff (full_name, pin_hmac, role, outlet_id) VALUES ('Cashier', repeat('a', 64), 'STAFF', 1)");
        execute("INSERT INTO timecard_event (staff_id, type, occurred_at, outlet_id) VALUES "
                + "(1, 'IN', now() - interval '5 hours', 1), (1, 'OUT', now() - interval '4 hours', 1)");
        execute("INSERT INTO timecard_event (staff_id, type, occurred_at, outlet_id, corrected_by, corrected_reason, corrected_at) "
                + "VALUES (1, 'OUT', now() - interval '1 hour', 1, 'Old Manager', 'Forgot', now())");
        flyway("35").migrate();
    }

    @AfterAll
    static void stop() throws Exception {
        if (db != null) {
            db.close();
        }
        postgres.stop();
    }

    @Test
    void existingCorrectionsKeepTheirNameAreMarkedLegacyAndNothingElseChanges() throws Exception {
        assertThat(text("SELECT corrected_by || '/' || corrected_by_type || '/' || coalesce(corrected_by_id::text, 'none') "
                + "FROM timecard_event WHERE corrected_by IS NOT NULL")).isEqualTo("Old Manager/LEGACY/none");
        assertThat(scalar("SELECT count(*) FROM timecard_event WHERE corrected_by IS NULL "
                + "AND corrected_by_type IS NULL AND corrected_by_id IS NULL")).isEqualTo(2);
    }

    @Test
    void theConstraintsKeepACorrectionsIdentityConsistent() {
        String prefix = "INSERT INTO timecard_event (staff_id, type, occurred_at, outlet_id, corrected_by, corrected_reason, corrected_at, "
                + "corrected_by_type, corrected_by_id) VALUES (1, 'OUT', now(), 1, 'Someone', 'r', now(), ";
        assertThatThrownBy(() -> execute(prefix + "'ADMIN', NULL)")).hasMessageContaining("timecard_event_corrector_ck");
        assertThatThrownBy(() -> execute(prefix + "'LEGACY', 5)")).hasMessageContaining("timecard_event_corrector_ck");
        assertThatThrownBy(() -> execute(prefix + "NULL, NULL)")).hasMessageContaining("timecard_event_corrector_ck");
        assertThatThrownBy(() -> execute(prefix + "'CASHIER', 5)")).hasMessageContaining("timecard_event_corrector_ck");
        assertThatThrownBy(() -> execute("INSERT INTO timecard_event (staff_id, type, occurred_at, outlet_id, corrected_by_type, corrected_by_id) "
                + "VALUES (1, 'OUT', now(), 1, 'ADMIN', 1)")).hasMessageContaining("violates check constraint");
    }

    @Test
    void theAuditLogRejectsUnknownActorsAndActionsAndCannotBeChanged() throws Exception {
        String insert = "INSERT INTO staff_audit (actor_type, actor_id, staff_id, action) VALUES ";
        assertThatThrownBy(() -> execute(insert + "('ROBOT', 1, 1, 'CREATE')")).hasMessageContaining("staff_audit_actor_type_ck");
        assertThatThrownBy(() -> execute(insert + "('ADMIN', 1, 1, 'DELETE_EVERYTHING')")).hasMessageContaining("staff_audit_action_ck");
        assertThatThrownBy(() -> execute(insert + "('ADMIN', 1, 999, 'CREATE')")).hasMessageContaining("foreign key");
        execute(insert + "('ADMIN', 1, 1, 'CREATE')");
        assertThatThrownBy(() -> execute("UPDATE staff_audit SET actor_id = 2")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute("DELETE FROM staff_audit")).hasMessageContaining("append-only");
        assertThat(scalar("SELECT count(*) FROM staff_audit WHERE occurred_at IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void theAuditQueriesUseTheirIndexes() throws Exception {
        execute("SET enable_seqscan = off");
        String byStaff = plan("SELECT action FROM staff_audit WHERE staff_id = 1 ORDER BY occurred_at DESC, id DESC");
        String byActor = plan("SELECT staff_id FROM staff_audit WHERE actor_type = 'ADMIN' AND actor_id = 1 ORDER BY occurred_at DESC, id DESC");
        String corrections = plan("SELECT id FROM timecard_event WHERE corrected_by IS NOT NULL "
                + "AND corrected_by_type = 'ADMIN' AND corrected_by_id = 1 ORDER BY corrected_at DESC");
        execute("RESET enable_seqscan");

        assertThat(byStaff).contains("staff_audit_staff_idx");
        assertThat(byActor).contains("staff_audit_actor_idx");
        assertThat(corrections).contains("timecard_event_corrector_idx");
    }

    private static String plan(String sql) throws SQLException {
        StringBuilder text = new StringBuilder();
        try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery("EXPLAIN " + sql)) {
            while (rows.next()) {
                text.append(rows.getString(1)).append('\n');
            }
        }
        return text.toString();
    }

    private static String text(String sql) throws SQLException {
        try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static long scalar(String sql) throws SQLException {
        try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Statement statement = db.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
