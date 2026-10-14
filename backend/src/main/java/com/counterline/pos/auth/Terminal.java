package com.counterline.pos.auth;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who is working and where: the staff member logged in, at the outlet their terminal is bound to.
 * Both come from the access token, so a terminal can only record sales for its own outlet.
 */
public record Terminal(Long staffId, Long outletId) {

    /** @throws BadCredentialsException for a token without an outlet (issued before outlets existed) */
    public static Terminal of(Jwt jwt) {
        Object outlet = jwt.getClaim(TokenService.OUTLET_CLAIM);
        if (!(outlet instanceof Number number)) {
            throw new BadCredentialsException("The session has no outlet; log in again");
        }
        return new Terminal(Long.valueOf(jwt.getSubject()), number.longValue());
    }
}
