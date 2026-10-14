package com.counterline.pos.auth;

import com.counterline.pos.common.ResourceNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who is using the back-office dashboard: a head-office admin (every outlet; {@code outletId}
 * null) or an outlet manager (their own outlet).
 */
public record DashboardUser(boolean admin, Long outletId) {

    public static DashboardUser of(Jwt jwt) {
        String role = jwt.getClaimAsString(TokenService.ROLE_CLAIM);
        if (TokenService.ADMIN_ROLE.equals(role)) {
            return new DashboardUser(true, null);
        }
        Object outlet = jwt.getClaim(TokenService.OUTLET_CLAIM);
        if ("MANAGER".equals(role) && outlet instanceof Number number) {
            return new DashboardUser(false, number.longValue());
        }
        throw new BadCredentialsException("Not a dashboard session");
    }

    /** Whether this user may see that outlet's sales. */
    public boolean canSee(Long outlet) {
        return admin || outletId.equals(outlet);
    }

    /** @throws ResourceNotFoundException for an outlet this user may not see (as if it didn't exist) */
    public void requireOutlet(Long outlet) {
        if (!canSee(outlet)) {
            throw new ResourceNotFoundException("Outlet " + outlet + " not found");
        }
    }
}
