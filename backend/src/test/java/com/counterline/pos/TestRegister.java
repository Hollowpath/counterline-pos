package com.counterline.pos;

import org.springframework.jdbc.core.JdbcTemplate;

/** Payments need an open register; tests that sell open one directly, at outlet 1 (created by the migrations). */
public final class TestRegister {

    private TestRegister() {
    }

    public static void open(JdbcTemplate jdbc, Long staffId) {
        jdbc.update("INSERT INTO register_session (outlet_id, opened_at, opened_by) VALUES (1, now() - interval '1 hour', ?)", staffId);
    }

    public static void close(JdbcTemplate jdbc) {
        jdbc.update("""
                UPDATE register_session SET closed_at = now(), closed_by = opened_by, version = version + 1
                WHERE closed_at IS NULL
                """);
    }
}
