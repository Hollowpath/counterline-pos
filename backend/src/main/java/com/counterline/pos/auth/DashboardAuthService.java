package com.counterline.pos.auth;

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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

/**
 * Logging in to the back-office dashboard with email and password: head office (every outlet) or
 * an outlet manager (their own outlet). Wrong passwords are throttled per client address.
 */
@Service
@Transactional(readOnly = true)
public class DashboardAuthService {

    private static final Logger log = LoggerFactory.getLogger(DashboardAuthService.class);
    /** Throttle key prefix: wrong passwords are counted per client address. */
    private static final String DASHBOARD = "dashboard:";

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
        limiter.checkAllowed(DASHBOARD + client);
        String normalised = email.strip().toLowerCase(Locale.ROOT);
        AdminUser admin = adminRepository.findByEmailAndActiveTrue(normalised).orElse(null);
        if (admin != null) {
            requirePassword(password, admin.getPasswordHash(), client);
            IssuedToken token = tokenService.issueAdmin(admin);
            log.info("Admin {} logged in to the dashboard", admin.getId());
            return new DashboardLoginResponse(token.value(), token.expiresAt(), admin.getFullName(), TokenService.ADMIN_ROLE,
                    null);
        }
        Staff manager = staffRepository.findByEmailAndActiveTrue(normalised)
                .filter(staff -> staff.getRole() == StaffRole.MANAGER)
                .orElse(null);
        requirePassword(password, manager == null ? null : manager.getPasswordHash(), client);
        IssuedToken token = tokenService.issueDashboard(manager);
        log.info("Manager {} logged in to the dashboard", manager.getId());
        return new DashboardLoginResponse(token.value(), token.expiresAt(), manager.getFullName(), StaffRole.MANAGER.name(),
                outletService.get(manager.getOutletId()));
    }

    /** {@code hash} null for an unknown email: checked anyway, so it takes as long as a wrong password. */
    private void requirePassword(String password, String hash, String client) {
        boolean matches = passwordEncoder.matches(password, hash == null ? unknownUserHash : hash);
        if (hash == null || !matches) {
            limiter.recordFailure(DASHBOARD + client);
            log.warn("Failed dashboard login from {}", client);
            throw new InvalidPinException("Wrong email or password");
        }
    }
}
