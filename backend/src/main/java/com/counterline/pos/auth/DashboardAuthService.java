package com.counterline.pos.auth;

import com.counterline.pos.auth.LoginAttemptLimiter.Attempt;
import com.counterline.pos.auth.LoginAttemptLimiter.Limit;
import com.counterline.pos.auth.TokenService.IssuedToken;
import com.counterline.pos.outlet.OutletService;
import com.counterline.pos.outlet.OutletService.OutletResponse;
import com.counterline.pos.staff.Staff;
import com.counterline.pos.staff.StaffRepository;
import com.counterline.pos.staff.StaffRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Logging in to the back-office dashboard with email and password: head office (every outlet) or
 * an outlet manager (their own outlet). Wrong passwords are throttled (see below). Not transactional: the login throttle
 * must run outside a transaction (see {@link LoginAttemptLimiter}), and the few reads here are single repository calls.
 */
@Service
public class DashboardAuthService {

    private static final Logger log = LoggerFactory.getLogger(DashboardAuthService.class);
    /**
     * Every attempt is admitted against three keys and wrong passwords are counted on all of them: the account on this
     * client (the e-mail typed, whether or not it exists, so the answer never tells which e-mails are accounts), the
     * client address as a wider net, and the account on all clients as a much wider one. So one client's failures lock
     * only that account on that client: they never lock the real user on another client, or a different account that
     * shares the address. The address locks after {@value #ADDRESS_FAILURE_FACTOR} times as many failures as one
     * account on one client does, and the account everywhere after {@value #ACCOUNT_FAILURE_FACTOR} times as many.
     * A right password gives back its own attempt only: it never clears anyone's failures.
     */
    private static final String DASHBOARD = "dashboard:";
    private static final String ACCOUNT = "dashboard-account:";
    private static final String CLIENT_ACCOUNT = "dashboard-client:";
    static final int ADDRESS_FAILURE_FACTOR = 5;
    static final int ACCOUNT_FAILURE_FACTOR = 10;

    private final AdminUserRepository adminRepository;
    private final StaffRepository staffRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final LoginAttemptLimiter limiter;
    private final OutletService outletService;
    /** Compared against when the email is unknown, so a wrong email takes as long as a wrong password. */
    private final String unknownUserHash;

    DashboardAuthService(AdminUserRepository adminRepository, StaffRepository staffRepository,
                         PasswordEncoder passwordEncoder, TokenService tokenService,
                         LoginAttemptLimiter limiter, OutletService outletService) {
        this.adminRepository = adminRepository;
        this.staffRepository = staffRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.limiter = limiter;
        this.outletService = outletService;
        this.unknownUserHash = passwordEncoder.encode("unknown-user");
    }

    /** {@code role} ADMIN or MANAGER; {@code outlet} is the manager's (null for head office). */
    public record DashboardLoginResponse(String token, Instant expiresAt, String name, String role, OutletResponse outlet) {
    }

    /** Head office (every outlet) or an outlet manager (their outlet), by email and password. */
    public DashboardLoginResponse login(String email, String password, String client) {
        String normalised = email.strip().toLowerCase(Locale.ROOT);
        int max = limiter.maxFailures();
        try (Attempt attempt = limiter.begin(
                new Limit(DASHBOARD + client, max * ADDRESS_FAILURE_FACTOR),
                new Limit(clientAccountKey(normalised, client), max),
                new Limit(accountKey(normalised), max * ACCOUNT_FAILURE_FACTOR))) {
            AdminUser admin = adminRepository.findByEmailAndActiveTrue(normalised).orElse(null);
            if (admin != null) {
                requirePassword(attempt, password, admin.getPasswordHash(), client);
                attempt.succeeded();
                IssuedToken token = tokenService.issueAdmin(admin);
                log.info("Admin {} logged in to the dashboard", admin.getId());
                return new DashboardLoginResponse(token.value(), token.expiresAt(), admin.getFullName(), TokenService.ADMIN_ROLE,
                        null);
            }
            Staff manager = staffRepository.findByEmailAndActiveTrue(normalised)
                    .filter(staff -> staff.getRole() == StaffRole.MANAGER)
                    .orElse(null);
            requirePassword(attempt, password, manager == null ? null : manager.getPasswordHash(), client);
            attempt.succeeded();
            IssuedToken token = tokenService.issueDashboard(manager);
            log.info("Manager {} logged in to the dashboard", manager.getId());
            return new DashboardLoginResponse(token.value(), token.expiresAt(), manager.getFullName(), StaffRole.MANAGER.name(),
                    outletService.get(manager.getOutletId()));
        }
    }

    /**
     * {@code hash} null for an unknown email: checked anyway, so it takes as long as a wrong password. A wrong password
     * counts on every key of the attempt (and may lock one), then is refused like any other.
     */
    private void requirePassword(Attempt attempt, String password, String hash, String client) {
        boolean matches = passwordEncoder.matches(password, hash == null ? unknownUserHash : hash);
        if (hash == null || !matches) {
            log.warn("Failed dashboard login from {}", client);
            attempt.failed();
            throw new InvalidPinException("Wrong email or password");
        }
    }

    /** The account on one client: a hash, so the key has a fixed length and no e-mail (real or not) or address is readable in it. */
    static String clientAccountKey(String normalisedEmail, String client) {
        return CLIENT_ACCOUNT + digest(normalisedEmail + "|" + client);
    }

    /** A hash of the typed e-mail, so the key has a fixed length and no e-mail (real or not) is stored in it. */
    static String accountKey(String normalisedEmail) {
        return ACCOUNT + digest(normalisedEmail);
    }

    private static String digest(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
