package com.counterline.pos.auth;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V31 (an attempt's place is settled by a decrement, so a row may briefly count nothing; the last admission is stamped in
 * {@code pending_at}) on databases that were at V29 and at V30 with throttle rows in them. Separate databases in one
 * PostgreSQL, migrated by hand to the earlier version, filled, then taken to V31.
 */
class LoginThrottleMigrationTest {

    /** The limiter's clock below: rows are stamped against it. */
    private static final Instant NOW = Instant.parse("2026-10-12T09:00:00Z");

    private static PostgreSQLContainer postgres;

    @BeforeAll
    static void start() throws Exception {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
        postgres.start();
        try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE from29");
            statement.execute("CREATE DATABASE from30");
        }
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    @Test
    void aDatabaseAtV29GetsBothStepsAndKeepsItsThrottleRows() throws Exception {
        String url = url("from29");
        flyway(url, "29").migrate();
        try (Connection db = connect(url)) {
            execute(db, "INSERT INTO login_throttle (throttle_key, failures, last_failed_at, locked_until) VALUES "
                    + "('till:1', 4, '2026-10-12 08:59:00+00', NULL), ('clock:1', 10, '2026-10-12 08:59:00+00', '2026-10-12 09:04:00+00')");
        }

        flyway(url, "31").migrate();

        try (Connection db = connect(url)) {
            assertThat(scalar(db, "SELECT failures FROM login_throttle WHERE throttle_key = 'till:1'")).isEqualTo(4);
            assertThat(scalar(db, "SELECT count(*) FROM login_throttle WHERE locked_until IS NOT NULL")).isEqualTo(1);
            assertThat(scalar(db, "SELECT sum(pending) FROM login_throttle")).isZero();
            assertThat(scalar(db, "SELECT count(*) FROM login_throttle WHERE pending_at IS NOT NULL")).isZero();
            assertRelaxedConstraint(db);
        }
    }

    @Test
    void aDatabaseAtV30KeepsItsAttemptsUnderWayAndTreatsAnUnstampedOneAsLongAgo() throws Exception {
        String url = url("from30");
        flyway(url, "30").migrate();
        try (Connection db = connect(url)) {
            // What V30 could hold: places under way, with no admission stamp (the column does not exist yet).
            execute(db, "INSERT INTO login_throttle (throttle_key, failures, pending, last_failed_at) VALUES "
                    + "('v30:fresh', 0, 2, '2026-10-12 08:59:00+00'), "
                    + "('v30:old', 0, 2, '2026-10-12 08:40:00+00'), "
                    + "('v30:oldother', 0, 1, '2026-10-12 08:40:00+00'), "
                    + "('v30:failures', 3, 1, '2026-10-12 08:59:00+00')");
        }

        flyway(url, "31").migrate();

        try (Connection db = connect(url)) {
            assertThat(scalar(db, "SELECT pending FROM login_throttle WHERE throttle_key = 'v30:fresh'")).isEqualTo(2);
            assertThat(scalar(db, "SELECT failures FROM login_throttle WHERE throttle_key = 'v30:failures'")).isEqualTo(3);
            assertThat(scalar(db, "SELECT count(*) FROM login_throttle WHERE pending_at IS NOT NULL")).isZero();
            assertRelaxedConstraint(db);
        }

        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(jdbc,
                new SecurityProperties("pepper", "x".repeat(32), Duration.ofHours(12), Duration.ofMinutes(5),
                        new SecurityProperties.LoginThrottle(10, Duration.ofMinutes(5))),
                Clock.fixed(NOW, ZoneOffset.UTC));

        // A window since its last wrong attempt and never stamped: the places are long gone, so a new attempt starts at one.
        limiter.begin(new LoginAttemptLimiter.Limit("v30:old", 10));
        assertThat(jdbc.queryForObject("SELECT pending FROM login_throttle WHERE throttle_key = 'v30:old'", Integer.class)).isEqualTo(1);
        // Within the window nothing is forgotten, stamped or not: the place counts like any other, and is now stamped.
        limiter.begin(new LoginAttemptLimiter.Limit("v30:fresh", 10));
        assertThat(jdbc.queryForObject("SELECT pending FROM login_throttle WHERE throttle_key = 'v30:fresh'", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_throttle WHERE throttle_key = 'v30:fresh' AND pending_at IS NOT NULL",
                Integer.class)).isEqualTo(1);
        // The sweep takes an old, unstamped row along with its places, and leaves the recent one.
        limiter.begin(new LoginAttemptLimiter.Limit("till:5", 10)).failed();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_throttle WHERE throttle_key = 'v30:oldother'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_throttle WHERE throttle_key = 'v30:failures'", Integer.class)).isEqualTo(1);
    }

    /** Exactly the existing constraint, replaced: nothing negative, and a row counting nothing is now allowed. */
    private static void assertRelaxedConstraint(Connection db) throws SQLException {
        assertThat(scalar(db, "SELECT count(*) FROM pg_constraint WHERE conname = 'login_throttle_counts_ck' "
                + "AND conrelid = 'login_throttle'::regclass")).isEqualTo(1);
        assertThat(scalar(db, "SELECT count(*) FROM pg_constraint WHERE conname = 'login_throttle_failures_ck'")).isZero();
        execute(db, "INSERT INTO login_throttle (throttle_key, failures, pending, last_failed_at) VALUES ('empty:1', 0, 0, now())");
        assertThatThrownBy(() -> execute(db, "INSERT INTO login_throttle (throttle_key, failures, pending, last_failed_at) VALUES ('neg:1', 1, -1, now())"))
                .isInstanceOf(SQLException.class).hasMessageContaining("login_throttle_counts_ck");
        assertThatThrownBy(() -> execute(db, "INSERT INTO login_throttle (throttle_key, failures, pending, last_failed_at) VALUES ('neg:2', -1, 1, now())"))
                .isInstanceOf(SQLException.class).hasMessageContaining("login_throttle_counts_ck");
        execute(db, "DELETE FROM login_throttle WHERE throttle_key = 'empty:1'");
        // The sweep and the admission read these two columns: the existing last-failed index serves the sweep.
        assertThat(scalar(db, "SELECT count(*) FROM pg_indexes WHERE indexname = 'login_throttle_last_failed_idx'")).isEqualTo(1);
    }

    private static String url(String database) {
        return postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + database);
    }

    private static Connection connect(String url) throws SQLException {
        Connection db = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
        db.setAutoCommit(true);
        return db;
    }

    private static long scalar(Connection db, String sql) throws SQLException {
        try (Statement statement = db.createStatement(); var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static void execute(Connection db, String sql) throws SQLException {
        try (Statement statement = db.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Flyway flyway(String url, String target) {
        return Flyway.configure()
                .dataSource(url, postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
