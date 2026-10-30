package com.counterline.pos.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * A manager's approval token ("Special Access Required") is for one operation ({@link ApprovalOperation})
 * and one use: the first use records its id, and any later use is refused. A token whose operation
 * is missing, unknown or not the one being done is refused and not used up.
 * <p>
 * A void or refund carries its token as the request's bearer token and records it at once (not in the
 * action's transaction), so two requests racing with the same token can't both get through. A
 * discount's token travels in the sale's body: {@link #consume} records it inside the sale's own
 * transaction, so a sale that is rolled back leaves it unused, and two sales racing with the same
 * token can't both commit (the second waits for the first, then finds it used).
 */
@Component
public class ApprovalTokens {

    /** Ids are kept a while past any approval's lifetime, then forgotten. */
    private static final Duration KEEP = Duration.ofDays(1);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    ApprovalTokens(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Uses up {@code jwt} as the approval of {@code operation} if it is an approval token; a till's own
     * session is not affected.
     *
     * @throws SpecialAccessDeniedException if the token is for another operation, or was used before
     */
    public void useOnce(Jwt jwt, ApprovalOperation operation) {
        if (TokenService.Channel.of(jwt.getClaim(TokenService.CHANNEL_CLAIM)) != TokenService.Channel.APPROVAL) {
            return;
        }
        consume(jwt, operation);
    }

    /**
     * Records the approval token {@code jwt} as used by {@code operation}, joining the transaction in
     * progress, if any.
     *
     * @throws SpecialAccessDeniedException if the token is for another operation, or was used before
     */
    void consume(Jwt jwt, ApprovalOperation operation) {
        if (ApprovalOperation.of(jwt.getClaim(TokenService.OPERATION_CLAIM)) != operation) {
            throw new SpecialAccessDeniedException("Manager approval needed");
        }
        Instant now = clock.instant();
        // Forgetting old ids never waits on, or deadlocks with, a sale that is deleting the same rows.
        jdbc.update("""
                DELETE FROM used_approval_token WHERE token_id IN (
                    SELECT token_id FROM used_approval_token WHERE used_at < ? FOR UPDATE SKIP LOCKED)""",
                Timestamp.from(now.minus(KEEP)));
        boolean first = jdbc.update("INSERT INTO used_approval_token (token_id, used_at) VALUES (?, ?) ON CONFLICT DO NOTHING",
                String.valueOf(jwt.getId()), Timestamp.from(now)) == 1;
        if (!first) {
            throw new SpecialAccessDeniedException("Approval used");
        }
    }
}
