package com.counterline.pos.loyalty;

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
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V29 (rolling expiry of points) on a database that was already at V28 and holds accounts and entries. The expiry
 * date is not stored anywhere (it follows from the member's purchases), so the migration changes no account: it
 * lets the ledger hold EXPIRE entries and indexes a member's counting purchases. A separate PostgreSQL, migrated by
 * hand to V28, filled, then taken to V29.
 */
class LoyaltyExpiryMigrationTest {

    private static PostgreSQLContainer postgres;
    private static Connection db;
    private static long member;

    @BeforeAll
    static void migrate() throws Exception {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
        postgres.start();
        flyway("28").migrate();
        db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        db.setAutoCommit(true);

        member = scalar("INSERT INTO customer (first_name, created_at, updated_at) VALUES ('With Entries', now(), now()) RETURNING id");
        execute("INSERT INTO loyalty_account (customer_id, points_balance, updated_at) VALUES (" + member + ", 9, now())");
        adjustment(10, Instant.parse("2025-01-01T02:00:00Z"));
        adjustment(-1, Instant.parse("2025-03-01T02:00:00Z"));

        flyway("29").migrate();
    }

    @AfterAll
    static void stop() throws Exception {
        if (db != null) {
            db.close();
        }
        postgres.stop();
    }

    @Test
    void existingAccountsAndEntriesAreUntouchedAndNoActivityDateIsStored() throws Exception {
        assertThat(scalar("SELECT points_balance FROM loyalty_account WHERE customer_id = " + member)).isEqualTo(9);
        assertThat(scalar("SELECT count(*) FROM loyalty_entry WHERE type = 'ADJUST'")).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM information_schema.columns WHERE table_name = 'loyalty_account' AND column_name LIKE '%activity%'"))
                .isZero();
        // An account written the old way still works.
        long fresh = scalar("INSERT INTO customer (first_name, created_at, updated_at) VALUES ('Fresh', now(), now()) RETURNING id");
        execute("INSERT INTO loyalty_account (customer_id, points_balance, updated_at) VALUES (" + fresh + ", 0, now())");
    }

    @Test
    void aMembersCountingPurchasesAreIndexedAndOnlyThoseThatAreCompletedAndNotRefunded() throws Exception {
        String definition = text("SELECT indexdef FROM pg_indexes WHERE indexname = 'sale_order_customer_purchase_idx'");
        assertThat(definition).contains("customer_id").contains("closed_at").contains("CLOSED").contains("refunded_at IS NULL");
    }

    @Test
    void anExpireEntryTakesPointsAwayAndBelongsToNoSaleAndNoStaffMember() throws Exception {
        execute("INSERT INTO loyalty_entry (customer_id, type, points, created_at) VALUES (" + member + ", 'EXPIRE', -5, now())");
        assertThat(scalar("SELECT count(*) FROM loyalty_entry WHERE type = 'EXPIRE'")).isEqualTo(1);

        // Never gives points, never nothing.
        assertThatThrownBy(() -> execute("INSERT INTO loyalty_entry (customer_id, type, points, created_at) VALUES (" + member + ", 'EXPIRE', 5, now())"))
                .hasMessageContaining("loyalty_entry_sign_ck");
        assertThatThrownBy(() -> execute("INSERT INTO loyalty_entry (customer_id, type, points, created_at) VALUES (" + member + ", 'EXPIRE', 0, now())"))
                .hasMessageContaining("loyalty_entry_sign_ck");
        // Not a sale's entry, and no staff reason.
        assertThatThrownBy(() -> execute("INSERT INTO loyalty_entry (customer_id, order_id, type, points, created_at) VALUES (" + member + ", 1, 'EXPIRE', -5, now())"))
                .hasMessageContaining("loyalty_entry_adjust_ck");
        assertThatThrownBy(() -> execute("INSERT INTO loyalty_entry (customer_id, type, points, created_at, reason, adjusted_by) VALUES ("
                + member + ", 'EXPIRE', -5, now(), 'Because', 'Someone')")).hasMessageContaining("loyalty_entry_adjust_ck");
    }

    @Test
    void adjustmentsKeepTheirRulesAndOtherTypesAreStillRefused() throws Exception {
        assertThatThrownBy(() -> execute("INSERT INTO loyalty_entry (customer_id, type, points, created_at) VALUES (" + member + ", 'ADJUST', 5, now())"))
                .hasMessageContaining("loyalty_entry_adjust_ck");
        assertThatThrownBy(() -> execute("INSERT INTO loyalty_entry (customer_id, type, points, created_at) VALUES (" + member + ", 'SPOIL', -5, now())"))
                .isInstanceOf(SQLException.class);
        assertThat(text("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'loyalty_entry_type_ck'")).contains("EXPIRE");
    }

    private static void adjustment(int points, Instant at) throws SQLException {
        execute("INSERT INTO loyalty_entry (customer_id, type, points, created_at, reason, adjusted_by) VALUES ("
                + member + ", 'ADJUST', " + points + ", '" + at + "', 'Seed', 'Head Office')");
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
