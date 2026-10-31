package com.counterline.pos.catalog;

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
 * V32 (a version on every catalogue row, for optimistic locking) on a database that was already at V31 and holds a tab, a
 * folder, items and an option group with options. A separate PostgreSQL, migrated by hand to V31, filled, then taken to V32.
 */
class CatalogVersionMigrationTest {

    private static PostgreSQLContainer postgres;
    private static Connection db;

    @BeforeAll
    static void migrate() throws Exception {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
        postgres.start();
        flyway("31").migrate();
        db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        db.setAutoCommit(true);

        execute("INSERT INTO quickgrid_tab (name, sort_order) VALUES ('Legacy Tab', 20)");
        execute("INSERT INTO category (name, tab_id, grid_row, grid_col) "
                + "SELECT 'Legacy Folder', id, 1, 1 FROM quickgrid_tab WHERE name = 'Legacy Tab'");
        execute("INSERT INTO item (category_id, name, price, sort_order) "
                + "SELECT id, 'Legacy Item', 12.50, 1 FROM category WHERE name = 'Legacy Folder'");
        execute("INSERT INTO modifier_group (item_id, name, choose_exactly, sort_order) "
                + "SELECT id, 'Legacy Group', 2, 1 FROM item WHERE name = 'Legacy Item'");
        execute("INSERT INTO modifier_option (group_id, name, price, sort_order) "
                + "SELECT id, 'Legacy Option', 1.00, 1 FROM modifier_group WHERE name = 'Legacy Group'");

        flyway("32").migrate();
    }

    @AfterAll
    static void stop() throws Exception {
        if (db != null) {
            db.close();
        }
        postgres.stop();
    }

    @Test
    void everyExistingRowStartsAtVersionZeroAndKeepsItsData() throws Exception {
        for (String table : new String[] {"quickgrid_tab", "category", "item", "modifier_group", "modifier_option"}) {
            assertThat(scalar("SELECT count(*) FROM " + table + " WHERE version <> 0")).as(table).isZero();
            assertThat(scalar("SELECT count(*) FROM " + table + " WHERE name LIKE 'Legacy%'")).as(table).isEqualTo(1);
        }
        assertThat(scalar("SELECT price * 100 FROM item WHERE name = 'Legacy Item'")).isEqualTo(1250);
        assertThat(scalar("SELECT choose_exactly FROM modifier_group WHERE name = 'Legacy Group'")).isEqualTo(2);
    }

    @Test
    void theVersionIsNeverNullAndANewRowWithoutOneStartsAtZero() throws Exception {
        execute("INSERT INTO quickgrid_tab (name, sort_order) VALUES ('Fresh Tab', 21)");
        assertThat(scalar("SELECT version FROM quickgrid_tab WHERE name = 'Fresh Tab'")).isZero();

        assertThatThrownBy(() -> execute("UPDATE item SET version = NULL WHERE name = 'Legacy Item'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("version");
        assertThatThrownBy(() -> execute("UPDATE modifier_option SET version = NULL WHERE name = 'Legacy Option'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("version");
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
