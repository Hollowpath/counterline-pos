package com.counterline.pos.auth;

import com.counterline.pos.auth.TokenService.Channel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

/**
 * Staff tokens (a till session, a manager's dashboard session, a manager's approval) are stateless
 * JWTs, so on every request each is checked against the database: the staff member must still be
 * active, hold the role and work at the outlet the token names, that outlet must be open, and the
 * token's session version must equal the current one. A dashboard token also needs the manager's
 * dashboard login to still exist. Deactivating someone, changing their role, outlet, PIN or login,
 * or closing their outlet raises the version, so the old token stays invalid even if the account,
 * outlet or login is later restored. A till or approval token must also name a till device that is still set up at its outlet. Head-office and customer-display tokens are not affected.
 * The refusal says nothing about the account.
 */
@Component
class StaffSessionGuard {

    private final JdbcTemplate jdbc;

    StaffSessionGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @throws InvalidBearerTokenException (401) if the token is a staff token that no longer holds */
    void check(Jwt jwt) {
        String role = jwt.getClaimAsString(TokenService.ROLE_CLAIM);
        if (!"STAFF".equals(role) && !"MANAGER".equals(role)) {
            return;
        }
        boolean dashboard = Channel.of(jwt.getClaim(TokenService.CHANNEL_CLAIM)) == Channel.DASHBOARD;
        Object outlet = jwt.getClaim(TokenService.OUTLET_CLAIM);
        Object version = jwt.getClaim(TokenService.SESSION_VERSION_CLAIM);
        // A till or approval token also names the till device it was issued on: that device must still be set up
        // at the token's outlet (a reset ends every session of the iPad at once). Dashboard tokens have none.
        long device = jwt.getClaim(TokenService.DEVICE_CLAIM) instanceof Number number ? number.longValue() : -1L;
        Long staffId = parseId(jwt.getSubject());
        // A token without the session version (issued before it existed) is never valid.
        boolean valid = staffId != null && outlet instanceof Number number && version instanceof Number sessionVersion
                && Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1 FROM staff s JOIN outlet o ON o.id = s.outlet_id
                            WHERE s.id = ? AND s.active AND s.role = ? AND s.outlet_id = ? AND o.active
                              AND s.auth_session_version = ? AND (? = FALSE OR s.email IS NOT NULL)
                              AND (? = TRUE OR EXISTS (SELECT 1 FROM till_device d
                                                       WHERE d.id = ? AND d.outlet_id = s.outlet_id AND d.reset_at IS NULL)))""",
                        Boolean.class, staffId, role, number.longValue(), sessionVersion.intValue(), dashboard, dashboard, device));
        if (!valid) {
            throw new InvalidBearerTokenException("The session is no longer valid");
        }
    }

    private static Long parseId(String subject) {
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
