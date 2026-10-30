package com.counterline.pos.timecards;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Who is clocked in. A shift is open while a staff member's latest event is an IN, and it belongs to
 * the outlet where that IN was made, whatever outlet the person works at later.
 */
@Component
public class OpenShifts {

    private final JdbcTemplate jdbc;

    OpenShifts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Whether this staff member is clocked in (anywhere). */
    public boolean has(Long staffId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT coalesce((SELECT type FROM timecard_event WHERE staff_id = ?
                                 ORDER BY occurred_at DESC, id DESC LIMIT 1), 'OUT') = 'IN'
                """, Boolean.class, staffId));
    }

    /** Whether anyone is clocked in at this outlet. */
    public boolean at(Long outletId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM timecard_event e
                    WHERE e.outlet_id = ? AND e.type = 'IN'
                      AND NOT EXISTS (SELECT 1 FROM timecard_event n WHERE n.staff_id = e.staff_id
                                      AND (n.occurred_at > e.occurred_at OR (n.occurred_at = e.occurred_at AND n.id > e.id))))
                """, Boolean.class, outletId));
    }
}
