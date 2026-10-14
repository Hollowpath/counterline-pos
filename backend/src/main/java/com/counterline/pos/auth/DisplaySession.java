package com.counterline.pos.auth;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;

/** A customer display's session: which display it is, at which outlet. From the display's token. */
public record DisplaySession(Long displayId, Long outletId) {

    public static DisplaySession of(Jwt jwt) {
        Object display = jwt.getClaim(TokenService.DISPLAY_CLAIM);
        Object outlet = jwt.getClaim(TokenService.OUTLET_CLAIM);
        if (!(display instanceof Number displayId) || !(outlet instanceof Number outletId)) {
            throw new BadCredentialsException("Not a customer display session");
        }
        return new DisplaySession(displayId.longValue(), outletId.longValue());
    }
}
