package com.counterline.pos.auth;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who made a change from the dashboard, for the record of it: a stable account id that survives renames, and the name
 * to show beside it (as it was at the time).
 *
 * @param type ADMIN: {@code id} is the head-office account's id. MANAGER: {@code id} is the manager's staff id.
 */
public record Actor(Type type, long id, String name) {

    public enum Type {
        ADMIN,
        MANAGER
    }

    private static final String ADMIN_PREFIX = "admin-";
    private static final int MAX_NAME = 80;
    private static final String UNNAMED = "Dashboard user";

    public Actor {
        if (type == null || id <= 0 || name == null || name.isBlank()) {
            throw new IllegalArgumentException("An actor needs a type, a stable id and a name");
        }
        name = name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
    }

    /** @throws BadCredentialsException unless the token is a head-office or manager dashboard session with a usable subject */
    public static Actor of(Jwt jwt) {
        String role = jwt.getClaimAsString(TokenService.ROLE_CLAIM);
        String subject = jwt.getSubject();
        String name = jwt.getClaimAsString("name");
        String display = name == null || name.isBlank() ? UNNAMED : name.strip();
        try {
            if (TokenService.ADMIN_ROLE.equals(role) && subject != null && subject.startsWith(ADMIN_PREFIX)) {
                return new Actor(Type.ADMIN, Long.parseLong(subject.substring(ADMIN_PREFIX.length())), display);
            }
            if ("MANAGER".equals(role) && subject != null) {
                return new Actor(Type.MANAGER, Long.parseLong(subject), display);
            }
        } catch (IllegalArgumentException e) {
            // A subject that is not an id: not an account this system issued.
        }
        throw new BadCredentialsException("Not a dashboard session");
    }
}
