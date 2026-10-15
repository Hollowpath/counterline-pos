package com.counterline.pos.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Throttles wrong PINs (and passwords, and display codes). A 6-digit PIN has only 10^6 values, so
 * unlimited guessing would find a valid one quickly.
 *
 * <p>Counted per key: an outlet's PIN pad ({@link PinPad#key}), or a client address where there is
 * nothing better. The {@code maxFailures}th wrong attempt locks the key for {@code window}; a gap of
 * {@code window} since the last wrong attempt clears the count, and so does an unlock. A right PIN
 * does not: the pads are shared, so anyone who knows their own PIN could otherwise clear the count
 * between guesses at everyone else's. The last few tries before the lock are warned about.
 *
 * <p>Kept in PostgreSQL, so every API task sees the same count and an unlock from the dashboard
 * reaches them all. Failures are recorded in a transaction of their own: the request that made them
 * fails, and rolls its own transaction back.
 */
@Component
public class LoginAttemptLimiter {

    /** A wrong attempt says how many are left once this few remain (the 7th to 9th of 10). */
    static final int WARN_WHEN_LEFT = 3;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate ownTransaction;
    private final int maxFailures;
    private final Duration window;
    private final Clock clock;

    LoginAttemptLimiter(JdbcTemplate jdbc, PlatformTransactionManager transactions, SecurityProperties properties,
                        Clock clock) {
        this.jdbc = jdbc;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.maxFailures = properties.loginThrottle().maxFailures();
        this.window = properties.loginThrottle().window();
        this.clock = clock;
    }

    /** A key that is locked now, and until when. */
    public record Lock(String key, Instant lockedUntil) {
    }

    private record Row(int failures, Instant lockedUntil) {
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
            throw locked(key, row.lockedUntil(), now);
        });
    }

    /**
     * Counts one wrong attempt.
     *
     * @return how many tries are left before the key locks (at least 1)
     * @throws TooManyLoginAttemptsException if this was the last try: the key is now locked
     */
    public int recordFailure(String key) {
        Instant now = clock.instant();
        Row row = ownTransaction.execute(status -> {
            // Rows that no longer count: the last wrong attempt is a window ago, or the lock is over.
            jdbc.update("DELETE FROM login_throttle WHERE last_failed_at <= ? AND (locked_until IS NULL OR locked_until <= ?)",
                    Timestamp.from(now.minus(window)), Timestamp.from(now));
            boolean first = jdbc.update("INSERT INTO login_throttle (throttle_key, failures, last_failed_at) VALUES (?, 1, ?) "
                    + "ON CONFLICT (throttle_key) DO NOTHING", key, Timestamp.from(now)) == 1;
            // Row lock: two wrong attempts at once both count.
            int failures = first ? 1 : find(key, true).orElseThrow().failures() + 1;
            Instant lockedUntil = failures >= maxFailures ? now.plus(window) : null;
            jdbc.update("UPDATE login_throttle SET failures = ?, last_failed_at = ?, locked_until = ? WHERE throttle_key = ?",
                    failures, Timestamp.from(now), lockedUntil == null ? null : Timestamp.from(lockedUntil), key);
            return new Row(failures, lockedUntil);
        });
        if (row.lockedUntil() != null) {
            throw locked(key, row.lockedUntil(), now);
        }
        return maxFailures - row.failures();
    }

    /** Unlocks the key at once, and forgets its wrong attempts. */
    public void unlock(String key) {
        ownTransaction.executeWithoutResult(status -> jdbc.update("DELETE FROM login_throttle WHERE throttle_key = ?", key));
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

    private TooManyLoginAttemptsException locked(String key, Instant lockedUntil, Instant now) {
        return new TooManyLoginAttemptsException(Duration.between(now, lockedUntil));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

}
