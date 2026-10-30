package com.counterline.pos.common;

import java.sql.SQLException;

/**
 * Tells a collision between concurrent changes from a real defect. A unique or foreign-key violation (two requests
 * took the same name or position, or something was removed under a request that refers to it) is a conflict to
 * retry; a check or not-null violation is a bug that must stay an error.
 */
public final class DatabaseConflicts {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private DatabaseConflicts() {
    }

    /** Whether {@code error} (or what caused it) names the database constraint {@code constraint}, e.g. a unique index. */
    public static boolean violates(Throwable error, String constraint) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("\"" + constraint + "\"")) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code error} (or what caused it) is PostgreSQL's unique or foreign-key violation. */
    public static boolean isCollision(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            String state = null;
            if (cause instanceof SQLException sql) {
                state = sql.getSQLState();
            } else if (cause instanceof org.hibernate.JDBCException jdbc) {
                state = jdbc.getSQLState();
            }
            if (UNIQUE_VIOLATION.equals(state) || FOREIGN_KEY_VIOLATION.equals(state)) {
                return true;
            }
        }
        return false;
    }
}
