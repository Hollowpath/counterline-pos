package com.counterline.pos.timecards;

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

/**
 * V34 drops the (staff_id, occurred_at DESC) index. First, at V33 where both indexes exist, the planner is made to prefer
 * indexes and shown that the lookups by latest event id use the new one and none uses the old one; then V34 removes it
 * and the new one is still there. A separate PostgreSQL, migrated by hand.
 */
class TimecardIndexMigrationTest {

    private static final String OLD = "timecard_event_staff_idx";
    private static final String NEW = "timecard_event_staff_latest_idx";

    private static PostgreSQLContainer postgres;
    private static Connection db;

    @BeforeAll
    static void start() throws Exception {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
        postgres.start();
        flyway("33").migrate();
        db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        db.setAutoCommit(true);
    }

    @AfterAll
    static void stop() throws Exception {
        if (db != null) {
            db.close();
        }
        postgres.stop();
    }

    @Test
    void theLookupsByLatestEventIdUseTheNewIndexAndNothingUsesTheOldOneThenItIsDropped() throws Exception {
        assertThat(indexes(OLD)).isEqualTo(1);
        assertThat(indexes(NEW)).isEqualTo(1);

        // Indexes preferred over scanning, so the plan shows which index a query can really use.
        execute("SET enable_seqscan = off");
        String latest = plan("SELECT type FROM timecard_event WHERE staff_id = 1 ORDER BY id DESC LIMIT 1");
        String open = plan("SELECT s.id FROM timecard_event e JOIN staff s ON s.id = e.staff_id "
                + "WHERE e.outlet_id = 1 AND e.type = 'IN' "
                + "AND NOT EXISTS (SELECT 1 FROM timecard_event n WHERE n.staff_id = e.staff_id AND n.id > e.id)");
        String byStaff = plan("SELECT 1 FROM timecard_event WHERE staff_id = 1");
        execute("RESET enable_seqscan");

        assertThat(latest).contains(NEW).doesNotContain(OLD);
        assertThat(open).contains(NEW).doesNotContain(OLD);
        // The foreign key to staff (a delete or update of a staff row looks events up by staff) is served by the new one too.
        assertThat(byStaff).contains(NEW).doesNotContain(OLD);

        flyway("34").migrate();

        assertThat(indexes(OLD)).isZero();
        assertThat(indexes(NEW)).isEqualTo(1);
        assertThat(indexes("timecard_event_outlet_occurred_idx")).isEqualTo(1);
    }

    private static long indexes(String name) throws SQLException {
        return scalar("SELECT count(*) FROM pg_indexes WHERE tablename = 'timecard_event' AND indexname = '" + name + "'");
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
