package com.counterline.pos.customers;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V26 (members are deactivated, never deleted) on a database that was already at V25 and holds the kinds of
 * rows the old rules allowed: trunk-zero numbers, and a deleted member whose phone, email or reference ID was
 * registered again. A separate PostgreSQL, migrated by hand to V25, filled, then taken to V26.
 */
class CustomerDeactivationMigrationTest {

    private static PostgreSQLContainer postgres;
    private static Connection db;

    /** Customer ids by the name each row was given. */
    private static final Map<String, Long> ids = new HashMap<>();
    private static Instant deletedAt;
    private static long orderOfDeleted;

    @BeforeAll
    static void migrate() throws Exception {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
        postgres.start();
        flyway("25").migrate();
        db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        db.setAutoCommit(true);

        deletedAt = Instant.parse("2026-09-01T03:00:00Z");

        // Phones. Name -> "state" (A active, D deleted).
        // 1. A trunk-zero number nobody else has.
        customer("trunk", "+600112345001", null, null, false);
        // 2. Its corrected form is another active member's: neither is touched.
        customer("trunk-clash", "+600112345002", null, null, false);
        customer("trunk-clash-holder", "+60112345002", null, null, false);
        // 3. A deleted member and an active one with the same phone (registered again after the deletion).
        customer("old-owner", "+60112345003", null, null, true);
        customer("new-owner", "+60112345003", null, null, false);
        // 4. Three deleted members with the same phone: the lowest id keeps it.
        customer("gone-1", "+60112345004", null, null, true);
        customer("gone-2", "+60112345004", null, null, true);
        customer("gone-3", "+60112345004", null, null, true);
        // 5. Two deleted trunk-zero members that become the same number: corrected, then the lowest id keeps it.
        customer("trunk-gone-1", "+600112345005", null, null, true);
        customer("trunk-gone-2", "+600112345005", null, null, true);
        // 6. A deleted trunk-zero member whose corrected number an active member has: left as it was.
        customer("trunk-gone-clash", "+600112345006", null, null, true);
        customer("trunk-gone-holder", "+60112345006", null, null, false);
        // 7. A foreign number is not touched.
        customer("foreign", "+6509123456", null, null, false);
        // 8. A number that merely contains 600 later on.
        customer("contains-600", "+60600112345", null, null, false);

        // Emails and reference IDs follow the same rules.
        customer("mail-old", "+60112340001", "same@example.com", "REF-1", true);
        customer("mail-new", "+60112340002", "same@example.com", "REF-1", false);
        customer("mail-gone-1", "+60112340003", "twin@example.com", "REF-2", true);
        customer("mail-gone-2", "+60112340004", "twin@example.com", "REF-2", true);
        customer("mail-unique", "+60112340005", "own@example.com", "REF-3", true);

        // History and points on members whose number is given up.
        long staff = scalar("INSERT INTO staff (full_name, pin_hmac, role, active, outlet_id) "
                + "VALUES ('Legacy Cashier', repeat('a', 64), 'STAFF', TRUE, 1) RETURNING id");
        orderOfDeleted = order(staff, ids.get("old-owner"), 1001);
        order(staff, ids.get("trunk-gone-2"), 1002);
        points(ids.get("old-owner"), orderOfDeleted, "EARN", 7);
        points(ids.get("old-owner"), orderOfDeleted, "REDEEM", -3);

        flyway("26").migrate();
    }

    @AfterAll
    static void stop() throws Exception {
        if (db != null) {
            db.close();
        }
        postgres.stop();
    }

    @Test
    void aTrunkZeroNumberBecomesTheProperOne() throws Exception {
        assertThat(phone("trunk")).isEqualTo("+60112345001");
        assertThat(phone("contains-600")).isEqualTo("+60600112345");
        assertThat(phone("foreign")).isEqualTo("+6509123456");
    }

    @Test
    void aCorrectedNumberAnotherMemberAlreadyHasLeavesBothAsTheyWere() throws Exception {
        assertThat(phone("trunk-clash")).isEqualTo("+600112345002");
        assertThat(phone("trunk-clash-holder")).isEqualTo("+60112345002");
        assertThat(phone("trunk-gone-clash")).isEqualTo("+600112345006");
        assertThat(phone("trunk-gone-holder")).isEqualTo("+60112345006");
    }

    @Test
    void anActiveMemberKeepsAContestedNumberFromADeletedOne() throws Exception {
        assertThat(phone("new-owner")).isEqualTo("+60112345003");
        assertThat(phone("old-owner")).isNull();
        assertThat(email("mail-new")).isEqualTo("same@example.com");
        assertThat(email("mail-old")).isNull();
        assertThat(reference("mail-new")).isEqualTo("REF-1");
        assertThat(reference("mail-old")).isNull();
    }

    @Test
    void amongDeletedMembersTheLowestIdKeepsAContestedValue() throws Exception {
        assertThat(ids.get("gone-1")).isLessThan(ids.get("gone-2")).isLessThan(ids.get("gone-3"));
        assertThat(phone("gone-1")).isEqualTo("+60112345004");
        assertThat(phone("gone-2")).isNull();
        assertThat(phone("gone-3")).isNull();
        // Corrected first, then the same rule.
        assertThat(phone("trunk-gone-1")).isEqualTo("+60112345005");
        assertThat(phone("trunk-gone-2")).isNull();
        assertThat(email("mail-gone-1")).isEqualTo("twin@example.com");
        assertThat(email("mail-gone-2")).isNull();
        assertThat(reference("mail-gone-1")).isEqualTo("REF-2");
        assertThat(reference("mail-gone-2")).isNull();
    }

    @Test
    void whatNobodyContestedIsLeftAlone() throws Exception {
        assertThat(email("mail-unique")).isEqualTo("own@example.com");
        assertThat(reference("mail-unique")).isEqualTo("REF-3");
        assertThat(phone("mail-unique")).isEqualTo("+60112340005");
    }

    @Test
    void everyMemberKeepsTheirIdNameHistoryAndPoints() throws Exception {
        assertThat(scalar("SELECT count(*) FROM customer")).isEqualTo(ids.size());
        for (Map.Entry<String, Long> member : ids.entrySet()) {
            assertThat(text("SELECT first_name FROM customer WHERE id = " + member.getValue())).isEqualTo(member.getKey());
        }
        // The member whose number was given up still has their order and points, and the order still names them.
        assertThat(scalar("SELECT customer_id FROM sale_order WHERE id = " + orderOfDeleted)).isEqualTo(ids.get("old-owner"));
        assertThat(scalar("SELECT customer_id FROM sale_order WHERE order_number = 1002")).isEqualTo(ids.get("trunk-gone-2"));
        assertThat(scalar("SELECT sum(points) FROM loyalty_entry WHERE customer_id = " + ids.get("old-owner"))).isEqualTo(4L);
        assertThat(scalar("SELECT count(*) FROM loyalty_entry WHERE customer_id = " + ids.get("old-owner"))).isEqualTo(2L);
    }

    @Test
    void theDeletedColumnIsNowTheDeactivatedOneWithTheSameTimes() throws Exception {
        assertThat(scalar("SELECT count(*) FROM information_schema.columns WHERE table_name = 'customer' AND column_name = 'deleted_at'")).isZero();
        assertThat(scalar("SELECT count(*) FROM information_schema.columns WHERE table_name = 'customer' AND column_name = 'deactivated_at'")).isEqualTo(1L);
        assertThat(timestamp("SELECT deactivated_at FROM customer WHERE id = " + ids.get("old-owner"))).isEqualTo(deletedAt);
        assertThat(timestamp("SELECT deactivated_at FROM customer WHERE id = " + ids.get("new-owner"))).isNull();
        assertThat(scalar("SELECT count(*) FROM customer WHERE deactivated_at IS NOT NULL")).isEqualTo(11L);
    }

    @Test
    void phoneEmailAndReferenceAreNowUniqueAcrossEveryMemberAndNullsAreStillMany() throws Exception {
        for (String index : new String[]{"customer_phone_uq", "customer_email_uq", "customer_reference_uq"}) {
            String definition = text("SELECT indexdef FROM pg_indexes WHERE tablename = 'customer' AND indexname = '" + index + "'");
            assertThat(definition).startsWith("CREATE UNIQUE INDEX").doesNotContain("WHERE");
        }
        // Even a deactivated member's number cannot be taken again.
        assertThatThrownBy(() -> customer("again", "+60112345003", null, null, false))
                .isInstanceOf(SQLException.class).hasMessageContaining("customer_phone_uq");
        assertThatThrownBy(() -> customer("again2", "+60112349999", "SAME@example.com".toLowerCase(), null, false))
                .isInstanceOf(SQLException.class).hasMessageContaining("customer_email_uq");
        assertThatThrownBy(() -> customer("again3", "+60112349998", null, "REF-3", false))
                .isInstanceOf(SQLException.class).hasMessageContaining("customer_reference_uq");
        // Members without a phone, email or reference ID are as many as there are.
        long before = scalar("SELECT count(*) FROM customer WHERE phone IS NULL");
        customer("blank-1", null, null, null, false);
        customer("blank-2", null, null, null, false);
        assertThat(scalar("SELECT count(*) FROM customer WHERE phone IS NULL")).isEqualTo(before + 2);
    }

    // ---- helpers ----

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static void customer(String name, String phone, String email, String reference, boolean deleted) throws SQLException {
        // Before V26 the column is deleted_at; after it, deactivated_at.
        String column = scalarOrZero("SELECT count(*) FROM information_schema.columns WHERE table_name = 'customer' AND column_name = 'deactivated_at'") > 0
                ? "deactivated_at" : "deleted_at";
        try (PreparedStatement insert = db.prepareStatement("INSERT INTO customer (first_name, phone, email, reference_id, created_at, updated_at, "
                + column + ") VALUES (?, ?, ?, ?, now(), now(), ?) RETURNING id")) {
            insert.setString(1, name);
            insert.setString(2, phone);
            insert.setString(3, email);
            insert.setString(4, reference);
            insert.setTimestamp(5, deleted ? Timestamp.from(deletedAt) : null);
            try (ResultSet result = insert.executeQuery()) {
                result.next();
                ids.put(name, result.getLong(1));
            }
        }
    }

    private static long order(long staff, long customer, long number) throws SQLException {
        return scalar("INSERT INTO sale_order (order_number, receipt_code, client_request_id, status, total, subtotal, staff_id, outlet_id, "
                + "created_at, customer_id) VALUES (" + number + ", 'LEGACY" + number + "', '" + UUID.randomUUID() + "', 'OPEN', 10, 10, "
                + staff + ", 1, now(), " + customer + ") RETURNING id");
    }

    private static void points(long customer, long order, String type, int points) throws SQLException {
        try (var statement = db.createStatement()) {
            statement.executeUpdate("INSERT INTO loyalty_entry (customer_id, order_id, type, points, created_at) VALUES ("
                    + customer + ", " + order + ", '" + type + "', " + points + ", now())");
        }
    }

    private static String phone(String name) throws SQLException {
        return text("SELECT phone FROM customer WHERE id = " + ids.get(name));
    }

    private static String email(String name) throws SQLException {
        return text("SELECT email FROM customer WHERE id = " + ids.get(name));
    }

    private static String reference(String name) throws SQLException {
        return text("SELECT reference_id FROM customer WHERE id = " + ids.get(name));
    }

    private static String text(String sql) throws SQLException {
        try (var statement = db.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private static Long scalar(String sql) throws SQLException {
        try (var statement = db.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static long scalarOrZero(String sql) throws SQLException {
        return scalar(sql);
    }

    private static Instant timestamp(String sql) throws SQLException {
        try (var statement = db.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            Timestamp value = result.getTimestamp(1);
            return value == null ? null : value.toInstant();
        }
    }
}
