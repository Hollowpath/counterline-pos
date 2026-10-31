package com.counterline.pos.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;

/**
 * Logging out ends the token that was presented, and only that one: its id is recorded until the time it would have
 * expired anyway (see {@link StaffSessionGuard}, which refuses it from then on). The same person's other sessions
 * (another iPad, the dashboard) go on.
 */
@Component
public class TokenRevocations {

    /** How many expired rows one logout sweeps away. */
    private static final int SWEEP = 100;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    TokenRevocations(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Ends {@code jwt} at once. Ending it again changes nothing. */
    public void revoke(Jwt jwt) {
        if (jwt.getId() == null || jwt.getExpiresAt() == null) {
            return;
        }
        jdbc.update("INSERT INTO revoked_token (token_id, expires_at) VALUES (?, ?) ON CONFLICT (token_id) DO NOTHING",
                jwt.getId(), Timestamp.from(jwt.getExpiresAt()));
        jdbc.update("""
                DELETE FROM revoked_token WHERE token_id IN (
                    SELECT token_id FROM revoked_token WHERE expires_at < ? LIMIT ?)""",
                Timestamp.from(clock.instant()), SWEEP);
    }
}
