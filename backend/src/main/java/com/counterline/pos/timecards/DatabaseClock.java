package com.counterline.pos.timecards;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * The one clock that dates clock events: the database server's. Every API server dates its events by the same clock, so
 * two servers a little apart cannot give one person's day an order it did not have, and the time is read when the event
 * is written (after the person's row is locked), so events recorded one after another are dated in that order.
 */
@Component
class DatabaseClock {

    private final JdbcTemplate jdbc;

    DatabaseClock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The database's wall clock right now ({@code clock_timestamp()}, not the transaction's start). */
    Instant now() {
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
    }
}
