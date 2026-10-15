package com.counterline.pos.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * A manager's approval token ("Special Access Required") opens one void or refund: the first use
 * records its id, and any later use is refused. Recorded at once (not in the action's transaction),
 * so two requests racing with the same token can't both get through.
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
     * Uses up {@code jwt} if it is an approval token; a till's own session is not affected.
     *
     * @throws SpecialAccessDeniedException if this approval was used before
     */
    public void useOnce(Jwt jwt) {
        if (TokenService.Channel.of(jwt.getClaim(TokenService.CHANNEL_CLAIM)) != TokenService.Channel.APPROVAL) {
            return;
        }
        Instant now = clock.instant();
        jdbc.update("DELETE FROM used_approval_token WHERE used_at < ?", Timestamp.from(now.minus(KEEP)));
        boolean first = jdbc.update("INSERT INTO used_approval_token (token_id, used_at) VALUES (?, ?) ON CONFLICT DO NOTHING",
                String.valueOf(jwt.getId()), Timestamp.from(now)) == 1;
        if (!first) {
            throw new SpecialAccessDeniedException("Approval used");
        }
    }
}
