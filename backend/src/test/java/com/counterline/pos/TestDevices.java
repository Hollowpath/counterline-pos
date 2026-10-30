package com.counterline.pos;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/** Till and approval tokens name the till device they were issued on; tests that mint them need one set up. */
public final class TestDevices {

    private TestDevices() {
    }

    /** The outlet's till device (set up now, by {@code staffId}, if it has none). */
    public static long active(JdbcTemplate jdbc, Long staffId, long outletId) {
        return jdbc.queryForList("SELECT id FROM till_device WHERE outlet_id = ? AND reset_at IS NULL", Long.class, outletId)
                .stream().findFirst().orElseGet(() -> jdbc.queryForObject("""
                        INSERT INTO till_device (outlet_id, label, key_hash, set_up_by, set_up_at)
                        VALUES (?, 'iPad T', ?, ?, now()) RETURNING id
                        """, Long.class, outletId, UUID.randomUUID().toString().replace("-", ""), staffId));
    }
}
