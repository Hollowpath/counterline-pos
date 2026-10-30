package com.counterline.pos.outlet;

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
 * V28 (versions on staff and outlets, outlet names unique ignoring case) on a database that was already at V27 and
 * holds outlets whose names differ only in case, as two simultaneous creations could have left them. A separate
 * PostgreSQL, migrated by hand to V27, filled, then taken to V28.
 */
class OutletVersionsMigrationTest {

    private static PostgreSQLContainer postgres;
    private static Connection db;

    @BeforeAll
    static void migrate() throws Exception {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
        postgres.start();
        flyway("27").migrate();
        db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        db.setAutoCommit(true);

        execute("INSERT INTO outlet (code, name) VALUES ('K1', 'Cafe'), ('K2', 'CAFE'), ('K3', 'cafe'), ('K4', 'Other')");
        execute("INSERT INTO outlet (code, name) VALUES ('K5', '" + "x".repeat(80) + "'), ('K6', '" + "X".repeat(80) + "')");
        execute("INSERT INTO staff (full_name, pin_hmac, role, active, outlet_id) "
                + "VALUES ('Legacy Manager', repeat('b', 64), 'MANAGER', TRUE, (SELECT id FROM outlet WHERE code = 'K1'))");

        flyway("28").migrate();
    }

    @AfterAll
    static void stop() throws Exception {
        if (db != null) {
            db.close();
        }
        postgres.stop();
    }

    @Test
    void theEarliestOutletKeepsItsNameAndLaterOnesGetTheirCodeAdded() throws Exception {
        assertThat(name("K1")).isEqualTo("Cafe");
        assertThat(name("K2")).isEqualTo("CAFE (K2)");
        assertThat(name("K3")).isEqualTo("cafe (K3)");
        assertThat(name("K4")).isEqualTo("Other");
    }

    @Test
    void aNameAtTheColumnLimitStillFitsWithItsCodeAdded() throws Exception {
        assertThat(name("K5")).isEqualTo("x".repeat(80));
        assertThat(name("K6")).hasSize(80).endsWith(" (K6)");
    }

    @Test
    void existingRowsStartAtVersionZeroAndTheIndexRefusesCaseVariants() throws Exception {
        assertThat(scalar("SELECT count(*) FROM outlet WHERE version <> 0")).isZero();
        assertThat(scalar("SELECT count(*) FROM staff WHERE version <> 0")).isZero();
        assertThatThrownBy(() -> execute("INSERT INTO outlet (code, name) VALUES ('K7', 'OTHER')"))
                .isInstanceOf(SQLException.class).hasMessageContaining("outlet_name_lower_uq");
    }

    private static String name(String code) throws SQLException {
        try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery("SELECT name FROM outlet WHERE code = '" + code + "'")) {
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
