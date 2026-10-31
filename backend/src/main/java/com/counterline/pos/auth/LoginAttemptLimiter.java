package com.counterline.pos.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Throttles wrong PINs (and passwords, and display codes). A 6-digit PIN has only 10^6 values, so
 * unlimited guessing would find a valid one quickly.
 *
 * <p>Counted per key: an outlet's PIN pad ({@link PinPad#key}), an account on a client, a client address.
 * An attempt is <em>admitted</em> before it is checked ({@link #begin}): one atomic statement counts it as under way on
 * every key, so a burst of parallel guesses cannot all get through; while a key's failures and attempts under way add up
 * to its {@code limit}, further attempts are refused until some settle. A wrong guess is then a
 * {@link Attempt#failed() failure}, and the {@code limit}th one locks the key for {@code window}; a right one is
 * {@link Attempt#succeeded() released} again, so a success neither counts nor clears anyone's failures (the pads are shared: anyone who knows their own PIN could
 * otherwise clear the count between guesses at everyone else's). A gap of {@code window} since the last wrong attempt
 * clears the count, and so does an unlock. The last few tries before the lock are warned about.
 *
 * <p>Fail-closed: an accounting statement that fails is never retried or undone, because whether it was applied is
 * unknown and a second try could take the place of another attempt. A place left counted that way (that, or a process that
 * died mid-attempt) is forgotten once a throttle window passes with no attempt admitted on its key ({@code pending_at}),
 * so until then it only makes that key a little stricter. The failures' window is not affected: admissions do not move it.
 *
 * <p>Kept in PostgreSQL, so every API task sees the same count and an unlock from the dashboard reaches them all.
 * Every statement here is a single autocommit statement on a connection taken and returned at once, so it must not be
 * called inside a transaction: a transaction would hold a second connection while this waits for one (the pool can run
 * dry under a burst of failed logins), and would roll the failure back with the failed request.
 */
@Component
public class LoginAttemptLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptLimiter.class);

    /** A wrong attempt says how many are left once this few remain (the 7th to 9th of 10). */
    static final int WARN_WHEN_LEFT = 3;

    /** How many stale rows one failure sweeps away. */
    private static final int SWEEP = 100;

    private final JdbcTemplate jdbc;
    private final int maxFailures;
    private final Duration window;
    private final Clock clock;

    LoginAttemptLimiter(JdbcTemplate jdbc, SecurityProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.maxFailures = properties.loginThrottle().maxFailures();
        this.window = properties.loginThrottle().window();
        this.clock = clock;
    }

    /** The failures in a row that lock an ordinary key (a PIN pad, one account on one client). */
    public int maxFailures() {
        return maxFailures;
    }

    /** A key that is locked now, and until when. */
    public record Lock(String key, Instant lockedUntil) {
    }

    /** A key and how many attempts it admits before it locks. */
    public record Limit(String key, int limit) {
    }

    private record Row(int failures, Instant lockedUntil) {
    }

    /**
     * An admitted attempt: settle it with {@link #succeeded()} or {@link #failed()}; closing it unsettled (an unexpected
     * error) gives its place back.
     */
    public final class Attempt implements AutoCloseable {

        /**
         * The keys whose place nobody has tried to settle yet. A key leaves this list <em>before</em> its statement runs
         * and is never put back: if the statement fails we cannot know whether it was applied (the connection may have
         * dropped after the commit), and doing it again could take the place of another attempt. So a failed key keeps
         * its place counted (fail-closed) until the quiet window forgets it, see the class comment.
         */
        private final List<Limit> open;
        private boolean settled;

        private Attempt(List<Limit> limits) {
            this.open = new ArrayList<>(limits);
        }

        /**
         * The guess was right: its place is given back on every key, once. Every key is tried even if one fails; the first
         * error is thrown after, the others attached to it. Closing the attempt afterwards does nothing more.
         */
        public void succeeded() {
            if (settled) {
                return;
            }
            RuntimeException error = null;
            for (Limit limit : List.copyOf(open)) {
                open.remove(limit);
                try {
                    release(limit.key());
                } catch (RuntimeException e) {
                    error = accumulate(error, e);
                }
            }
            settled = true;
            if (error != null) {
                throw error;
            }
        }

        /**
         * The guess was wrong: it counts on every key.
         *
         * @return how many tries are left before the first key locks (at least 1)
         * @throws TooManyLoginAttemptsException if this was the last try on some key: it is now locked
         */
        public int failed() {
            if (settled) {
                throw new IllegalStateException("The attempt is already settled");
            }
            Instant now = clock.instant();
            sweepQuietly(now);
            Duration locked = null;
            int left = Integer.MAX_VALUE;
            RuntimeException error = null;
            for (Limit limit : List.copyOf(open)) {
                open.remove(limit);
                try {
                    Row row = fail(limit, now);
                    if (row.lockedUntil() != null && row.lockedUntil().isAfter(now)) {
                        Duration remaining = Duration.between(now, row.lockedUntil());
                        locked = locked == null || remaining.compareTo(locked) > 0 ? remaining : locked;
                    } else {
                        left = Math.min(left, limit.limit() - row.failures());
                    }
                } catch (RuntimeException e) {
                    // The other keys are still counted; this one is left as it is (see the list of open keys).
                    error = accumulate(error, e);
                }
            }
            settled = true;
            if (locked != null) {
                TooManyLoginAttemptsException lock = new TooManyLoginAttemptsException(locked);
                if (error != null) {
                    lock.addSuppressed(error);
                }
                throw lock;
            }
            if (error != null) {
                throw error;
            }
            return Math.max(1, left);
        }

        @Override
        public void close() {
            succeeded();
        }
    }

    /**
     * Admits one attempt against every key, atomically per key.
     *
     * @throws TooManyLoginAttemptsException if a key is locked, or has as many attempts under way as it admits
     */
    public Attempt begin(Limit... limits) {
        requireNoTransaction();
        Instant now = clock.instant();
        List<Limit> admitted = new ArrayList<>();
        RuntimeException failure = null;
        for (Limit limit : limits) {
            try {
                Duration refused = admit(limit.key(), limit.limit(), now);
                if (refused != null) {
                    failure = new TooManyLoginAttemptsException(refused);
                    break;
                }
                admitted.add(limit);
            } catch (RuntimeException e) {
                failure = e;
                break;
            }
        }
        if (failure != null) {
            // Only the keys this call admitted give a place back, latest first, each even if another fails to; the refusal
            // (or the error) stays what is thrown, with the failed clean-ups attached.
            for (int i = admitted.size() - 1; i >= 0; i--) {
                try {
                    release(admitted.get(i).key());
                } catch (RuntimeException e) {
                    failure.addSuppressed(e);
                }
            }
            throw failure;
        }
        return new Attempt(List.copyOf(admitted));
    }

    private static RuntimeException accumulate(RuntimeException error, RuntimeException next) {
        if (error == null) {
            return next;
        }
        error.addSuppressed(next);
        return error;
    }

    /**
     * Counts one use of a quota (such as the sign-ups a display may take): at most {@code limit} in a fixed window that
     * starts with the first. Use {@link #refund} for a use that did not happen after all.
     *
     * @throws TooManyLoginAttemptsException when the quota is used up, until the window ends
     */
    public void consume(String key, int limit) {
        requireNoTransaction();
        Instant now = clock.instant();
        Timestamp stale = Timestamp.from(now.minus(window));
        boolean sawRow = false;
        for (int tries = 0; tries < 3; tries++) {
            int updated = jdbc.update("""
                    UPDATE login_throttle SET
                        failures = CASE WHEN last_failed_at <= ? THEN 1 ELSE failures + 1 END,
                        last_failed_at = CASE WHEN last_failed_at <= ? THEN ? ELSE last_failed_at END
                    WHERE throttle_key = ? AND (failures < ? OR last_failed_at <= ?)""",
                    stale, stale, Timestamp.from(now), key, limit, stale);
            if (updated == 1 || jdbc.update("INSERT INTO login_throttle (throttle_key, failures, last_failed_at) VALUES (?, 1, ?) "
                    + "ON CONFLICT (throttle_key) DO NOTHING", key, Timestamp.from(now)) == 1) {
                return;
            }
            // One lookup of the window's start; the row may be gone by now (swept, or tidied after a refund), and then the
            // loop simply counts again.
            List<Timestamp> windowStarts = jdbc.queryForList("SELECT last_failed_at FROM login_throttle WHERE throttle_key = ?",
                    Timestamp.class, key);
            if (!windowStarts.isEmpty()) {
                if (!sawRow) {
                    // A parallel first use made the row between our update and our insert: count against it once more.
                    sawRow = true;
                    continue;
                }
                throw new TooManyLoginAttemptsException(Duration.between(now, windowStarts.get(0).toInstant().plus(window)));
            }
        }
        throw new TooManyLoginAttemptsException(Duration.ofSeconds(1));
    }

    /** Gives back one use of a quota. */
    public void refund(String key) {
        requireNoTransaction();
        // One statement takes the use back, judged on the row as it is when it runs, so it counts exactly once whatever
        // changed the row meanwhile; tidying an empty row away comes after and nothing depends on it.
        jdbc.update("UPDATE login_throttle SET failures = failures - 1 WHERE throttle_key = ? AND failures > 0", key);
        deleteIfEmpty(key);
    }

    /** Whole seconds until the key unlocks (rounded up), or 0 when it isn't locked. */
    public long lockedForSeconds(String key) {
        Instant now = clock.instant();
        return find(key, false).filter(row -> locked(row, now))
                .map(row -> (Duration.between(now, row.lockedUntil()).toMillis() + 999) / 1000)
                .orElse(0L);
    }

    /** @throws TooManyLoginAttemptsException while the key is locked */
    public void checkAllowed(String key) {
        Instant now = clock.instant();
        find(key, false).filter(row -> locked(row, now)).ifPresent(row -> {
            throw new TooManyLoginAttemptsException(Duration.between(now, row.lockedUntil()));
        });
    }

    /**
     * Counts one wrong attempt as if it had been admitted and failed in one go (see {@link #begin} for the real paths,
     * where the attempt is admitted before it is checked).
     *
     * @return how many tries are left before the key locks (at least 1)
     * @throws TooManyLoginAttemptsException if this was the last try: the key is now locked
     */
    public int recordFailure(String key) {
        return recordFailure(key, maxFailures);
    }

    /** As {@link #recordFailure(String)}, for a key with a limit of its own. */
    public int recordFailure(String key, int limit) {
        requireNoTransaction();
        Instant now = clock.instant();
        sweepQuietly(now);
        Duration refused = admit(key, limit, now);
        if (refused != null) {
            throw new TooManyLoginAttemptsException(refused);
        }
        // If this fails the outcome is unknown, so nothing is undone: the admitted place stays counted until the quiet
        // window forgets it (see the class comment), rather than a guess at what to give back taking another's place.
        Row row = fail(new Limit(key, limit), now);
        if (row.lockedUntil() != null && row.lockedUntil().isAfter(now)) {
            throw new TooManyLoginAttemptsException(Duration.between(now, row.lockedUntil()));
        }
        return Math.max(1, limit - row.failures());
    }

    /**
     * Unlocks the key at once, and forgets its wrong attempts. The attempts under way on it stay counted (the row is only
     * tidied away if nothing at all is counted on it), so their places are still theirs when they settle.
     */
    public void unlock(String key) {
        jdbc.update("UPDATE login_throttle SET failures = 0, locked_until = NULL WHERE throttle_key = ?", key);
        deleteIfEmpty(key);
    }

    /** The keys starting with {@code prefix} that are locked now. */
    public List<Lock> locked(String prefix) {
        Instant now = clock.instant();
        return jdbc.query("SELECT throttle_key, locked_until FROM login_throttle "
                        + "WHERE throttle_key LIKE ? AND locked_until > ? ORDER BY throttle_key",
                (rs, rowNum) -> new Lock(rs.getString("throttle_key"), instant(rs, "locked_until")),
                prefix.replace("%", "\\%").replace("_", "\\_") + "%", Timestamp.from(now));
    }

    /** {@code message}, and how many tries are left once there are only a few. */
    public String withTriesLeft(String message, int left) {
        if (left > WARN_WHEN_LEFT) {
            return message;
        }
        return message + " · " + left + (left == 1 ? " try" : " tries") + " left";
    }

    // ---- the statements ----

    /**
     * Counts the attempt on the key unless the key is locked or already has {@code limit} attempts counted; a row
     * whose last wrong attempt is a window ago starts again. Null when admitted, else how long to wait.
     */
    private Duration admit(String key, int limit, Instant now) {
        Timestamp stale = Timestamp.from(now.minus(window));
        boolean sawRow = false;
        for (int tries = 0; tries < 3; tries++) {
            // A window since the last wrong attempt forgets the failures and the lock, and starts the window again at once
            // (no sliding). The attempts under way are forgotten only when none was admitted for a window either: one
            // that is still running when the row crosses the cutoff keeps its place.
            int updated = jdbc.update("""
                    UPDATE login_throttle SET
                        pending = CASE WHEN last_failed_at <= ? AND COALESCE(pending_at, '-infinity') <= ? THEN 1 ELSE pending + 1 END,
                        failures = CASE WHEN last_failed_at <= ? THEN 0 ELSE failures END,
                        locked_until = CASE WHEN last_failed_at <= ? THEN NULL ELSE locked_until END,
                        last_failed_at = CASE WHEN last_failed_at <= ? THEN ? ELSE last_failed_at END,
                        pending_at = ?
                    WHERE throttle_key = ? AND (locked_until IS NULL OR locked_until <= ?)
                      AND (CASE WHEN last_failed_at <= ? THEN 0 ELSE failures END
                         + CASE WHEN last_failed_at <= ? AND COALESCE(pending_at, '-infinity') <= ? THEN 0 ELSE pending END) < ?""",
                    stale, stale, stale, stale, stale, Timestamp.from(now), Timestamp.from(now),
                    key, Timestamp.from(now), stale, stale, stale, limit);
            if (updated == 1 || insertPending(key, now)) {
                return null;
            }
            Optional<Row> row = find(key, false);
            if (row.isPresent()) {
                if (locked(row.get(), now)) {
                    return Duration.between(now, row.get().lockedUntil());
                }
                if (!sawRow) {
                    // A parallel first attempt made the row between our update and our insert: it is not necessarily
                    // full, so count against it once more before saying it is.
                    sawRow = true;
                    continue;
                }
                // The attempts under way fill it: they settle in moments.
                return Duration.ofSeconds(1);
            }
            // The row vanished between the two statements (swept or unlocked): count again.
        }
        return Duration.ofSeconds(1);
    }

    private boolean insertPending(String key, Instant now) {
        return jdbc.update("INSERT INTO login_throttle (throttle_key, failures, pending, last_failed_at, pending_at) "
                + "VALUES (?, 0, 1, ?, ?) ON CONFLICT (throttle_key) DO NOTHING",
                key, Timestamp.from(now), Timestamp.from(now)) == 1;
    }

    /** A wrong attempt on the key: stamps it, and locks the key if it is the {@code limit}th. */
    private Row fail(Limit limit, Instant now) {
        Timestamp lockedUntil = Timestamp.from(now.plus(window));
        return jdbc.queryForObject("""
                INSERT INTO login_throttle (throttle_key, failures, pending, last_failed_at, locked_until)
                VALUES (?, 1, 0, ?, CASE WHEN 1 >= ? THEN ?::timestamptz END)
                ON CONFLICT (throttle_key) DO UPDATE SET
                    failures = login_throttle.failures + 1,
                    pending = GREATEST(login_throttle.pending - 1, 0),
                    last_failed_at = EXCLUDED.last_failed_at,
                    locked_until = CASE WHEN login_throttle.failures + 1 >= ? THEN ?::timestamptz ELSE login_throttle.locked_until END
                RETURNING failures, locked_until""",
                (rs, rowNum) -> new Row(rs.getInt("failures"), instant(rs, "locked_until")),
                limit.key(), Timestamp.from(now), limit.limit(), lockedUntil, limit.limit(), lockedUntil);
    }

    /**
     * Gives back the place of an attempt under way. One statement takes it back, judged on the row as it is when it runs,
     * so it counts exactly once whatever changed the row meanwhile (and never below zero); tidying an empty row away comes
     * after, and nothing depends on it.
     */
    private void release(String key) {
        jdbc.update("UPDATE login_throttle SET pending = pending - 1 WHERE throttle_key = ? AND pending > 0", key);
        deleteIfEmpty(key);
    }

    /** Deletes the row if nothing is counted on it (an attempt admitted meanwhile makes this skip it). A failure is only logged. */
    private void deleteIfEmpty(String key) {
        try {
            jdbc.update("DELETE FROM login_throttle WHERE throttle_key = ? AND failures = 0 AND pending = 0 "
                    + "AND (locked_until IS NULL OR locked_until <= ?)", key, Timestamp.from(clock.instant()));
        } catch (RuntimeException e) {
            log.warn("Login throttle could not tidy away an empty row: {}", e.toString());
        }
    }

    /**
     * Rows that no longer count: the last wrong attempt is a window ago, any lock is over and no attempt was admitted
     * for a window. A few at a time.
     */
    private void sweep(Instant now) {
        jdbc.update("""
                DELETE FROM login_throttle
                WHERE last_failed_at <= ? AND (locked_until IS NULL OR locked_until <= ?)
                  AND (pending = 0 OR COALESCE(pending_at, '-infinity') <= ?)
                  AND throttle_key IN (
                    SELECT throttle_key FROM login_throttle
                    WHERE last_failed_at <= ? AND (locked_until IS NULL OR locked_until <= ?)
                      AND (pending = 0 OR COALESCE(pending_at, '-infinity') <= ?) LIMIT ?)""",
                Timestamp.from(now.minus(window)), Timestamp.from(now), Timestamp.from(now.minus(window)),
                Timestamp.from(now.minus(window)), Timestamp.from(now), Timestamp.from(now.minus(window)), SWEEP);
    }

    /** Housekeeping must not stop an attempt being counted or settled: a failed sweep is logged and left for the next. */
    private void sweepQuietly(Instant now) {
        try {
            sweep(now);
        } catch (RuntimeException e) {
            log.warn("Login throttle sweep failed: {}", e.toString());
        }
    }

    private Optional<Row> find(String key, boolean forUpdate) {
        List<Row> rows = jdbc.query("SELECT failures, locked_until FROM login_throttle WHERE throttle_key = ?"
                        + (forUpdate ? " FOR UPDATE" : ""),
                (rs, rowNum) -> new Row(rs.getInt("failures"), instant(rs, "locked_until")),
                key);
        return rows.stream().findFirst();
    }

    private static boolean locked(Row row, Instant now) {
        return row.lockedUntil() != null && row.lockedUntil().isAfter(now);
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The login throttle must be used outside a transaction");
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
