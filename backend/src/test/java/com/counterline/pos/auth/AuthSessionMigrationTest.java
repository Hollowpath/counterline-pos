package com.counterline.pos.auth;

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
 * V30 (head-office session versions, logged-out tokens, the throttle's attempts under way) on a database that was already
 * at V29 and holds an admin account and throttle rows. A separate PostgreSQL, migrated by hand to V29, filled, then taken to V30.
 */
class AuthSessionMigrationTest {

    private static PostgreSQLContainer postgres;
    private static Connection db;

    @BeforeAll
    static void migrate() throws Exception {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
        postgres.start();
        flyway("29").migrate();
        db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        db.setAutoCommit(true);

        execute("INSERT INTO admin_user (email, full_name, password_hash, active, created_at) "
                + "VALUES ('head@legacy.test', 'Head Office', 'x', TRUE, now())");
        execute("INSERT INTO login_throttle (throttle_key, failures, last_failed_at, locked_until) VALUES "
                + "('till:1', 4, now(), NULL), ('clock:1', 10, now(), now() + interval '5 minutes')");

        flyway("30").migrate();
    }

    @AfterAll
    static void stop() throws Exception {
        if (db != null) {
            db.close();
        }
        postgres.stop();
    }

    @Test
    void existingAccountsStartAtVersionZeroAndExistingThrottleRowsKeepTheirFailuresWithNothingUnderWay() throws Exception {
        assertThat(scalar("SELECT auth_session_version FROM admin_user WHERE email = 'head@legacy.test'")).isZero();
        assertThat(scalar("SELECT failures FROM login_throttle WHERE throttle_key = 'till:1'")).isEqualTo(4);
        assertThat(scalar("SELECT sum(pending) FROM login_throttle WHERE throttle_key IN ('till:1', 'clock:1')")).isZero();
        assertThat(scalar("SELECT count(*) FROM login_throttle WHERE locked_until IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void aThrottleRowCountsSomethingAndNeverNegativeAndATokenIsRecordedOnce() throws Exception {
        // A row with an attempt under way and no failure yet is allowed; one with nothing counted, or less, is not.
        execute("INSERT INTO login_throttle (throttle_key, failures, pending, last_failed_at) VALUES ('till:9', 0, 1, now())");
        assertThatThrownBy(() -> execute("INSERT INTO login_throttle (throttle_key, failures, pending, last_failed_at) VALUES ('till:8', 0, 0, now())"))
                .isInstanceOf(SQLException.class).hasMessageContaining("login_throttle_counts_ck");
        assertThatThrownBy(() -> execute("INSERT INTO login_throttle (throttle_key, failures, pending, last_failed_at) VALUES ('till:7', 1, -1, now())"))
                .isInstanceOf(SQLException.class).hasMessageContaining("login_throttle_counts_ck");

        execute("INSERT INTO revoked_token (token_id, expires_at) VALUES ('abc', now() + interval '1 hour')");
        assertThatThrownBy(() -> execute("INSERT INTO revoked_token (token_id, expires_at) VALUES ('abc', now() + interval '1 hour')"))
                .isInstanceOf(SQLException.class).hasMessageContaining("revoked_token_pkey");
        assertThat(scalar("SELECT count(*) FROM pg_indexes WHERE indexname IN ('revoked_token_expires_idx', 'login_throttle_last_failed_idx')"))
                .isEqualTo(2);
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
