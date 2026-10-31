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
import java.util.Locale;
import java.util.UUID;

/**
 * Issues the signed access tokens: who it is, their role, the outlet, and the channel the token was
 * issued through, which limits what it opens (see {@link SecurityConfig}).
 */
@Service
public class TokenService {

    static final String ISSUER = "counterline-pos";
    static final String ROLE_CLAIM = "role";
    static final String OUTLET_CLAIM = "outlet";
    static final String DISPLAY_CLAIM = "display";
    static final String CHANNEL_CLAIM = "channel";
    /** An account's authentication session version, a staff member's or a head-office account's (see StaffSessionGuard). */
    static final String SESSION_VERSION_CLAIM = "sver";
    /** The till device (iPad) a till or approval token was issued on; resetting the device ends the token (see StaffSessionGuard). */
    static final String DEVICE_CLAIM = "device";
    /** The one action an approval token authorises (see {@link ApprovalOperation}). */
    static final String OPERATION_CLAIM = "operation";
    /** The role of a customer display's token: it may call only the display API. */
    public static final String DISPLAY_ROLE = "DISPLAY";
    /** A head-office account on the back-office dashboard: every outlet, no till. */
    public static final String ADMIN_ROLE = "ADMIN";

    /** How a token was issued. A token without the claim counts as {@link #TILL}. */
    public enum Channel {
        /** A PIN login on the outlet's set-up iPad: the POS API. */
        TILL,
        /** An email and password login on the dashboard: the dashboard API and customers only. */
        DASHBOARD,
        /** A manager's PIN for one restricted action at a till: a void or refund, used once. */
        APPROVAL;

        String claim() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Channel of(Object claim) {
            if (claim == null) {
                return TILL;
            }
            for (Channel channel : values()) {
                if (channel.claim().equals(claim.toString())) {
                    return channel;
                }
            }
            // An unknown channel opens nothing a till token would.
            return APPROVAL;
        }
    }

    private final JwtEncoder encoder;
    private final Duration ttl;
    private final Clock clock;

    TokenService(JwtEncoder encoder, SecurityProperties properties, Clock clock) {
        this.encoder = encoder;
        this.ttl = properties.tokenTtl();
        this.clock = clock;
    }

    /** Whether {@code jwt} is a manager's or head office's dashboard session (not a till's). */
    public static boolean isDashboardSession(org.springframework.security.oauth2.jwt.Jwt jwt) {
        return Channel.of(jwt.getClaim(CHANNEL_CLAIM)) == Channel.DASHBOARD;
    }

    /** A staff member's session at the outlet's till, on the till device {@code deviceId}. */
    public IssuedToken issue(Staff staff, Long outletId, Long deviceId) {
        return staffToken(staff, outletId, deviceId, ttl, Channel.TILL, null);
    }

    /** An outlet manager's session on the dashboard: it does not open the till's API. */
    public IssuedToken issueDashboard(Staff manager) {
        return staffToken(manager, manager.getOutletId(), null, ttl, Channel.DASHBOARD, null);
    }

    /**
     * "Special Access Required": a manager's approval of one {@code operation} at {@code outletId}, for
     * {@code lifetime}. Only that operation accepts it, once.
     */
    public IssuedToken issueApproval(Staff manager, Long outletId, Long deviceId, Duration lifetime, ApprovalOperation operation) {
        return staffToken(manager, outletId, deviceId, lifetime, Channel.APPROVAL, operation);
    }

    private IssuedToken staffToken(Staff staff, Long outletId, Long deviceId, Duration lifetime, Channel channel,
                                   ApprovalOperation operation) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(lifetime);
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .id(UUID.randomUUID().toString())
                .subject(staff.getId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("name", staff.getFullName())
                .claim(ROLE_CLAIM, staff.getRole().name())
                .claim(OUTLET_CLAIM, outletId)
                .claim(CHANNEL_CLAIM, channel.claim())
                .claim(SESSION_VERSION_CLAIM, staff.getAuthSessionVersion());
        if (deviceId != null) {
            builder.claim(DEVICE_CLAIM, deviceId);
        }
        if (operation != null) {
            builder.claim(OPERATION_CLAIM, operation.name());
        }
        return sign(builder.build(), expiresAt);
    }

    /** For a head-office admin on the dashboard (no outlet: they see every outlet). */
    public IssuedToken issueAdmin(AdminUser admin) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .id(UUID.randomUUID().toString())
                .subject("admin-" + admin.getId())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("name", admin.getFullName())
                .claim(ROLE_CLAIM, ADMIN_ROLE)
                .claim(CHANNEL_CLAIM, Channel.DASHBOARD.claim())
                .claim(SESSION_VERSION_CLAIM, admin.getAuthSessionVersion())
                .build();
        return sign(claims, expiresAt);
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
        return sign(claims, expiresAt);
    }

    private IssuedToken sign(JwtClaimsSet claims, Instant expiresAt) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return new IssuedToken(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), expiresAt);
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }
}
