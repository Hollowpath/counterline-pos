package com.counterline.pos.staff;

import com.counterline.pos.auth.Actor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The append-only record of changes to staff ({@code staff_audit}): who (type and stable id), to whom, what, and when
 * (the database's clock). Written in the caller's transaction, so a change and its record stand or fall together.
 * It holds no PIN, password, hash or e-mail, only that the thing was done.
 */
@Component
public class StaffAuditLog {

    public enum Action {
        CREATE,
        EDIT,
        PIN_RESET,
        LOGIN_CHANGED,
        LOGIN_REMOVED,
        DEACTIVATE,
        REACTIVATE
    }

    private final JdbcTemplate jdbc;

    StaffAuditLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void record(Actor actor, Long staffId, Action action) {
        jdbc.update("INSERT INTO staff_audit (actor_type, actor_id, staff_id, action) VALUES (?, ?, ?, ?)",
                actor.type().name(), actor.id(), staffId, action.name());
    }
}
