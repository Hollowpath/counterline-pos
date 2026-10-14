package com.counterline.pos.auth;

import com.counterline.pos.staff.Staff;
import com.counterline.pos.staff.StaffRepository;
import com.counterline.pos.staff.StaffRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * Creates demo staff for local development only. Done in code rather than SQL because the stored
 * PIN hash depends on the configured pepper. The demo PINs are listed in the README.
 */
@Component
@Profile("local")
class DemoStaffSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoStaffSeeder.class);

    private final StaffRepository staffRepository;
    private final PinHasher pinHasher;
    private final JdbcTemplate jdbc;
    private final AdminUserRepository adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    DemoStaffSeeder(StaffRepository staffRepository, PinHasher pinHasher, JdbcTemplate jdbc,
                    AdminUserRepository adminRepository, PasswordEncoder passwordEncoder, Clock clock) {
        this.staffRepository = staffRepository;
        this.pinHasher = pinHasher;
        this.jdbc = jdbc;
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /** The demo head-office account for the dashboard (local only; listed in the README). */
    private static final String DEMO_ADMIN_EMAIL = "admin@counterline.test";
    private static final String DEMO_ADMIN_PASSWORD = "counterline-demo";

    /**
     * Demo staff per outlet (outlet code → name, PIN, role); each logs in only at their own outlet.
     * Managers also log in to the dashboard, with {@code email} and the demo password.
     */
    private record DemoStaff(String outletCode, String fullName, String pin, StaffRole role, String email) {
    }

    private static final List<DemoStaff> DEMO_STAFF = List.of(
            new DemoStaff("O1", "Aisyah Rahman", "111111", StaffRole.STAFF, null),
            new DemoStaff("O1", "Daniel Lim", "999999", StaffRole.MANAGER, "daniel@counterline.test"),
            new DemoStaff("O2", "Farid Hassan", "222222", StaffRole.STAFF, null),
            new DemoStaff("O2", "Mei Ling Tan", "888888", StaffRole.MANAGER, "meiling@counterline.test"));

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!adminRepository.existsByEmail(DEMO_ADMIN_EMAIL)) {
            adminRepository.save(new AdminUser(DEMO_ADMIN_EMAIL, "Head Office", passwordEncoder.encode(DEMO_ADMIN_PASSWORD),
                    clock.instant()));
            log.info("Seeded the demo head-office account for local development");
        }
        // Idempotent: adds whichever demo staff are missing (e.g. a new outlet's), and the managers'
        // dashboard logins.
        for (DemoStaff demo : DEMO_STAFF) {
            String pinHmac = pinHasher.hash(demo.pin());
            Staff staff = staffRepository.findByPinHmacAndActiveTrue(pinHmac).orElse(null);
            if (staff == null) {
                Long outletId = jdbc.queryForList("SELECT id FROM outlet WHERE code = ?", Long.class, demo.outletCode())
                        .stream().findFirst().orElse(null);
                if (outletId == null) {
                    continue;
                }
                staff = staffRepository.save(new Staff(demo.fullName(), pinHmac, demo.role(), outletId));
                log.info("Seeded demo staff {} for local development", demo.fullName());
            }
            if (demo.email() != null && staff.getEmail() == null) {
                staff.setDashboardLogin(demo.email(), passwordEncoder.encode(DEMO_ADMIN_PASSWORD));
                log.info("Seeded the demo dashboard login of {} for local development", demo.fullName());
            }
        }
    }
}
