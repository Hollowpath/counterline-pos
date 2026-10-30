package com.counterline.pos.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The catalogue's one write lock. Every change to tabs, folders, items, option groups and options (creating,
 * editing, moving, reordering, removing) takes it first, and holds it until its transaction ends, so the changes
 * happen one at a time and each sees the last one's result: no two creates pick the same next position, a
 * reorder never overlaps a create or a delete, two folders cannot be moved into one grid cell at once, and a tab
 * made on the fly is made once.
 *
 * <p>Deadlocks cannot come from it: it is always the first lock a catalogue change takes, and no other code takes
 * it. Reading the catalogue (the till, the dashboard's lists) takes no lock. Removing an item also locks that
 * item's row, after this lock, which is what makes a sale and a removal of the same item meet in order (see
 * {@code CatalogAdminService#removeItem}). A collision that still gets past, such as a sale that commits an
 * item the moment it is deleted, comes back as a 409 ({@code CatalogErrorHandler}), never a 500.
 */
@Component
class CatalogLock {

    /** An advisory lock key of its own (the customer displays use another). */
    private static final long KEY = 0x0c47_a109_0000_0001L;

    private final JdbcTemplate jdbc;

    CatalogLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Waits for any other catalogue change to finish. Must run inside the transaction that makes the change. */
    void acquire() {
        jdbc.queryForObject("select 1 from (select pg_advisory_xact_lock(?)) as locked", Integer.class, KEY);
    }
}
