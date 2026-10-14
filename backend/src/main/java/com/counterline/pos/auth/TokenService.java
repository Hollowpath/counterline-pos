package com.counterline.pos.auth;

import com.counterline.pos.staff.Staff;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Issues the signed access token returned by a successful PIN login: the staff member, their role and the terminal's outlet. */
@Service
public class TokenService {

    static final String ISSUER = "counterline-pos";
    static final String ROLE_CLAIM = "role";
    static final String OUTLET_CLAIM = "outlet";
    static final String DISPLAY_CLAIM = "display";
    /** The role of a customer display's token: it may call only the display API. */
    public static final String DISPLAY_ROLE = "DISPLAY";
    /** A head-office account on the back-office dashboard: every outlet, no till. */
    public static final String ADMIN_ROLE = "ADMIN";

    private final JwtEncoder encoder;
    private final Duration ttl;
    private final Clock clock;

    TokenService(JwtEncoder encoder, SecurityProperties properties, Clock clock) {
        this.encoder = encoder;
        this.ttl = properties.tokenTtl();
        this.clock = clock;
    }

    public IssuedToken issue(Staff staff, Long outletId) {
        return issue(staff, outletId, ttl);
    }

    public IssuedToken issue(Staff staff, Long outletId, Duration lifetime) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(lifetime);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(staff.getId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("name", staff.getFullName())
                .claim(ROLE_CLAIM, staff.getRole().name())
                .claim(OUTLET_CLAIM, outletId)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    /** For a head-office admin on the dashboard (no outlet: they see every outlet). */
    public IssuedToken issueAdmin(AdminUser admin) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject("admin-" + admin.getId())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("name", admin.getFullName())
                .claim(ROLE_CLAIM, ADMIN_ROLE)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return new IssuedToken(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), expiresAt);
    }

    /** For a customer display paired with a POS terminal at {@code outletId}. */
    public IssuedToken issueDisplay(Long displayId, Long outletId, Duration lifetime) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(lifetime);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject("display-" + displayId)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(ROLE_CLAIM, DISPLAY_ROLE)
                .claim(OUTLET_CLAIM, outletId)
                .claim(DISPLAY_CLAIM, displayId)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return new IssuedToken(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), expiresAt);
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }
}
