package com.counterline.pos.auth;

import com.counterline.pos.staff.Staff;
import com.counterline.pos.staff.StaffRepository;
import com.counterline.pos.staff.StaffRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Creates the first head-office account and the first manager of an existing outlet in a fresh
 * database, from {@code pos.bootstrap.*}. It does not depend on the {@code local} profile and is
 * disabled unless {@code pos.bootstrap.enabled=true}.
 *
 * <p>Re-running is safe: identities that already match are left untouched, and existing
 * credentials are never overwritten. Anything that conflicts (an e-mail or PIN already held by a
 * different account, or an unknown outlet) stops the start-up. Messages and logs name the
 * property or the kind of problem only; no PIN, password, hash or configured value is logged.
 */
@Component
@ConditionalOnProperty(prefix = "pos.bootstrap", name = "enabled", havingValue = "true")
class BootstrapInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapInitializer.class);

    private static final Pattern PIN = Pattern.compile("\\d{6}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    static final int MIN_PASSWORD_LENGTH = 12;
    /** BCrypt ignores (or rejects) anything beyond 72 bytes, so a longer password is refused. */
    static final int MAX_PASSWORD_BYTES = 72;

    private final BootstrapProperties properties;
    private final StaffRepository staffRepository;
    private final AdminUserRepository adminRepository;
    private final PinHasher pinHasher;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Environment environment;

    BootstrapInitializer(BootstrapProperties properties, StaffRepository staffRepository,
                         AdminUserRepository adminRepository, PinHasher pinHasher,
                         PasswordEncoder passwordEncoder, JdbcTemplate jdbc, Clock clock,
                         Environment environment) {
        this.environment = environment;
        this.properties = properties;
        this.staffRepository = staffRepository;
        this.adminRepository = adminRepository;
        this.pinHasher = pinHasher;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (environment.acceptsProfiles(Profiles.of("local"))) {
            throw failure("pos.bootstrap.enabled cannot be used with the local profile, which seeds its own sample accounts");
        }
        validate();
        String outletCode = properties.outletCode().strip();
        String adminEmail = normalise(properties.headOfficeEmail());
        String managerEmail = normalise(properties.managerEmail());

        Long outletId = jdbc.queryForList("SELECT id FROM outlet WHERE code = ? AND active", Long.class, outletCode)
                .stream().findFirst().orElse(null);
        if (outletId == null) {
            throw failure("the outlet named by pos.bootstrap.outlet-code does not exist or is not active");
        }

        ensureHeadOffice(adminEmail);
        ensureManager(outletId, managerEmail);
    }

    private void ensureHeadOffice(String email) {
        if (staffRepository.findByEmail(email).isPresent()) {
            throw failure("pos.bootstrap.head-office-email is already the dashboard login of a staff member");
        }
        AdminUser existing = adminRepository.findByEmail(email).orElse(null);
        if (existing == null) {
            adminRepository.save(new AdminUser(email, properties.headOfficeName().strip(),
                    passwordEncoder.encode(properties.headOfficePassword()), clock.instant()));
            log.info("Bootstrap: created the head-office account");
            return;
        }
        if (!existing.isActive()) {
            throw failure("pos.bootstrap.head-office-email belongs to a deactivated head-office account");
        }
        log.info("Bootstrap: the head-office account already exists; its credentials were left unchanged");
    }

    private void ensureManager(Long outletId, String email) {
        if (adminRepository.existsByEmail(email)) {
            throw failure("pos.bootstrap.manager-email is already a head-office account");
        }
        String pinHmac = pinHasher.hash(properties.managerPin());
        Staff byPin = staffRepository.findByPinHmac(pinHmac).orElse(null);
        Staff byEmail = staffRepository.findByEmail(email).orElse(null);

        if (byPin == null && byEmail == null) {
            Staff manager = staffRepository.save(
                    new Staff(properties.managerName().strip(), pinHmac, StaffRole.MANAGER, outletId));
            manager.setDashboardLogin(email, passwordEncoder.encode(properties.managerPassword()));
            log.info("Bootstrap: created the outlet manager");
            return;
        }
        if (byPin == null) {
            throw failure("pos.bootstrap.manager-email is already the dashboard login of a different staff member");
        }
        boolean sameManager = byPin.isActive() && byPin.getRole() == StaffRole.MANAGER
                && outletId.equals(byPin.getOutletId()) && email.equals(byPin.getEmail());
        if (!sameManager) {
            throw failure("pos.bootstrap.manager-pin is already held by a different staff member");
        }
        log.info("Bootstrap: the outlet manager already exists; the credentials were left unchanged");
    }

    private void validate() {
        List<String> problems = new ArrayList<>();
        requireText(problems, "outlet-code", properties.outletCode(), 10);
        requireText(problems, "head-office-name", properties.headOfficeName(), 100);
        requireEmail(problems, "head-office-email", properties.headOfficeEmail());
        requirePassword(problems, "head-office-password", properties.headOfficePassword());
        requireText(problems, "manager-name", properties.managerName(), 120);
        if (properties.managerPin() == null || !PIN.matcher(properties.managerPin()).matches()) {
            problems.add("manager-pin must be exactly 6 digits");
        }
        requireEmail(problems, "manager-email", properties.managerEmail());
        requirePassword(problems, "manager-password", properties.managerPassword());
        if (properties.headOfficeEmail() != null && properties.managerEmail() != null
                && normalise(properties.headOfficeEmail()).equals(normalise(properties.managerEmail()))) {
            problems.add("manager-email must differ from head-office-email");
        }
        if (!problems.isEmpty()) {
            throw failure("invalid pos.bootstrap configuration: " + String.join("; ", problems));
        }
    }

    private static void requireText(List<String> problems, String name, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            problems.add(name + " is required");
        } else if (value.strip().length() > maxLength) {
            problems.add(name + " must be at most " + maxLength + " characters");
        }
    }

    private static void requireEmail(List<String> problems, String name, String value) {
        if (value == null || value.isBlank()) {
            problems.add(name + " is required");
        } else if (value.strip().length() > 254 || !EMAIL.matcher(value.strip()).matches()) {
            problems.add(name + " must be a valid e-mail address");
        }
    }

    private static void requirePassword(List<String> problems, String name, String value) {
        if (value == null || value.isBlank()) {
            problems.add(name + " is required");
        } else if (value.codePointCount(0, value.length()) < MIN_PASSWORD_LENGTH) {
            problems.add(name + " must be at least " + MIN_PASSWORD_LENGTH + " characters");
        } else if (value.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            problems.add(name + " must be at most " + MAX_PASSWORD_BYTES + " bytes in UTF-8");
        }
    }

    /** Dashboard logins are matched on the stripped, lower-case e-mail (see DashboardAuthService). */
    private static String normalise(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static IllegalStateException failure(String message) {
        return new IllegalStateException("Bootstrap refused: " + message);
    }
}
