package com.counterline.pos.auth;

import com.counterline.pos.TestcontainersConfiguration;
import com.counterline.pos.staff.Staff;
import com.counterline.pos.staff.StaffRepository;
import com.counterline.pos.staff.StaffRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The first-run initializer against PostgreSQL. Every credential here is generated per test run;
 * none is written in the source. The initializer is built directly (the shared context has
 * bootstrap disabled) and run in a transaction, as the application does.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class BootstrapInitializerIntegrationTest {

    @Autowired
    ApplicationContext context;
    @Autowired
    StaffRepository staffRepository;
    @Autowired
    AdminUserRepository adminRepository;
    @Autowired
    PinHasher pinHasher;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Clock clock;
    @Autowired
    PlatformTransactionManager transactionManager;

    private final String adminPassword = secret();
    private final String managerPassword = secret();
    private final String pin = randomPin();

    @BeforeEach
    void emptyDatabase() {
        jdbc.execute("TRUNCATE login_throttle, staff, admin_user RESTART IDENTITY CASCADE");
        jdbc.update("UPDATE outlet SET active = TRUE");
    }

    @Test
    void isNotRegisteredUnlessEnabled() {
        assertThat(context.getBeanNamesForType(BootstrapInitializer.class)).isEmpty();
        assertThat(count("admin_user")).isZero();
        assertThat(count("staff")).isZero();
    }

    @Test
    void createsTheHeadOfficeAccountAndAManagerForTheOutlet() {
        run(config());

        AdminUser admin = adminRepository.findByEmail("head@bootstrap.test").orElseThrow();
        assertThat(admin.getFullName()).isEqualTo("Head Office Person");
        assertThat(admin.isActive()).isTrue();
        assertThat(passwordEncoder.matches(adminPassword, admin.getPasswordHash())).isTrue();

        Staff manager = staffRepository.findByEmail("manager@bootstrap.test").orElseThrow();
        assertThat(manager.getRole()).isEqualTo(StaffRole.MANAGER);
        assertThat(manager.isActive()).isTrue();
        assertThat(manager.getOutletId()).isEqualTo(outletId("O1"));
        assertThat(staffRepository.findByPinHmacAndActiveTrue(pinHasher.hash(pin))).get().extracting(Staff::getId)
                .isEqualTo(manager.getId());
        assertThat(passwordEncoder.matches(managerPassword, manager.getPasswordHash())).isTrue();
        assertThat(count("admin_user")).isEqualTo(1);
        assertThat(count("staff")).isEqualTo(1);
    }

    @Test
    void rerunsChangeNothingAndNeverOverwriteCredentials() {
        run(config());
        String adminHash = adminRepository.findByEmail("head@bootstrap.test").orElseThrow().getPasswordHash();
        String managerHash = staffRepository.findByEmail("manager@bootstrap.test").orElseThrow().getPasswordHash();

        run(config());
        // The same accounts with different passwords: still not overwritten, and not an error.
        run(withPasswords(secret(), secret()));

        assertThat(count("admin_user")).isEqualTo(1);
        assertThat(count("staff")).isEqualTo(1);
        assertThat(adminRepository.findByEmail("head@bootstrap.test").orElseThrow().getPasswordHash()).isEqualTo(adminHash);
        assertThat(staffRepository.findByEmail("manager@bootstrap.test").orElseThrow().getPasswordHash())
                .isEqualTo(managerHash);
    }

    @Test
    void rejectsMissingConfigurationNamingOnlyTheProperties() {
        BootstrapProperties empty = new BootstrapProperties(true, null, " ", null, "", null, null, null, null);

        assertThatThrownBy(() -> run(empty)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outlet-code is required")
                .hasMessageContaining("head-office-name is required")
                .hasMessageContaining("head-office-email is required")
                .hasMessageContaining("head-office-password is required")
                .hasMessageContaining("manager-name is required")
                .hasMessageContaining("manager-pin must be exactly 6 digits")
                .hasMessageContaining("manager-email is required")
                .hasMessageContaining("manager-password is required");
        assertNothingCreated();
    }

    @Test
    void rejectsInvalidValuesWithoutEchoingThem() {
        String shortPassword = "short-" + new Random().nextInt(10);
        String longPassword = "x".repeat(73);
        String badPin = "12345" + new Random().nextInt(9) + "7";
        BootstrapProperties invalid = new BootstrapProperties(true, "O1", "Head", "not-an-email", shortPassword,
                "Manager", badPin, "head@bootstrap.test", longPassword);

        assertThatThrownBy(() -> run(invalid)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("head-office-email must be a valid e-mail address")
                .hasMessageContaining("head-office-password must be at least")
                .hasMessageContaining("manager-pin must be exactly 6 digits")
                .hasMessageContaining("manager-password must be at most")
                .hasMessageNotContaining(shortPassword).hasMessageNotContaining(badPin)
                .hasMessageNotContaining(longPassword).hasMessageNotContaining("not-an-email");
        assertNothingCreated();
    }

    @Test
    void refusesToRunWithTheLocalProfileAndCreatesNothing() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");

        assertThatThrownBy(() -> run(config(), local)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be used with the local profile")
                .hasMessageNotContaining(pin).hasMessageNotContaining(adminPassword)
                .hasMessageNotContaining(managerPassword).hasMessageNotContaining("bootstrap.test");
        assertNothingCreated();
    }

    @Test
    void enforcesThePasswordContractInCharactersAndUtf8Bytes() {
        // 11 characters is too short; 12 is enough.
        assertThatThrownBy(() -> run(withAdminPassword("a".repeat(11)))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("head-office-password must be at least 12 characters");
        // Two-byte characters: 36 of them are exactly 72 bytes (accepted); 37 are 74 bytes (refused),
        // although 37 characters is well within a character limit.
        assertThatThrownBy(() -> run(withAdminPassword("é".repeat(37)))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("head-office-password must be at most 72 bytes in UTF-8");
        // 35 two-byte characters plus 3 ASCII is 73 bytes (refused); plus 2 is 72 bytes (accepted).
        assertThatThrownBy(() -> run(withAdminPassword("é".repeat(35) + "aaa"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at most 72 bytes in UTF-8");
        assertNothingCreated();

        String boundaryPassword = "é".repeat(35) + "aa";
        run(withAdminPassword(boundaryPassword));
        assertThat(passwordEncoder.matches(boundaryPassword,
                adminRepository.findByEmail("head@bootstrap.test").orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void rejectsTheSameEmailForBothAccounts() {
        BootstrapProperties same = new BootstrapProperties(true, "O1", "Head", "Same@Bootstrap.test", adminPassword,
                "Manager", pin, " same@bootstrap.TEST ", managerPassword);

        assertThatThrownBy(() -> run(same)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("manager-email must differ from head-office-email");
        assertNothingCreated();
    }

    @Test
    void failsWhenTheOutletDoesNotExistOrIsInactive() {
        assertThatThrownBy(() -> run(withOutlet("ZZ99"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outlet-code does not exist or is not active");
        assertNothingCreated();

        jdbc.update("UPDATE outlet SET active = FALSE WHERE code = 'O1'");
        assertThatThrownBy(() -> run(config())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not exist or is not active");
        assertNothingCreated();
    }

    @Test
    void failsWhenTheHeadOfficeEmailIsADeactivatedAdmin() {
        adminRepository.save(new AdminUser("head@bootstrap.test", "Former", passwordEncoder.encode(secret()), clock.instant()));
        jdbc.update("UPDATE admin_user SET active = FALSE");

        assertThatThrownBy(() -> run(config())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deactivated head-office account");
        assertThat(count("staff")).isZero();
    }

    @Test
    void failsWhenTheHeadOfficeEmailIsAStaffDashboardLogin() {
        saveManager("Someone", randomPin(), "head@bootstrap.test");

        assertThatThrownBy(() -> run(config())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("head-office-email is already the dashboard login of a staff member");
        assertThat(count("admin_user")).isZero();
    }

    @Test
    void failsWhenTheManagerEmailIsAHeadOfficeAccountAndRollsBackTheRest() {
        adminRepository.save(new AdminUser("manager@bootstrap.test", "Other Head", passwordEncoder.encode(secret()),
                clock.instant()));

        assertThatThrownBy(() -> run(config())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("manager-email is already a head-office account");
        // The head-office account of this run was not left behind.
        assertThat(adminRepository.findByEmail("head@bootstrap.test")).isEmpty();
        assertThat(count("staff")).isZero();
    }

    @Test
    void failsWhenTheManagerEmailBelongsToADifferentStaffMember() {
        saveManager("Other Manager", randomPinOtherThan(pin), "manager@bootstrap.test");

        assertThatThrownBy(() -> run(config())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("manager-email is already the dashboard login of a different staff member");
        assertThat(count("staff")).isEqualTo(1);
        assertThat(count("admin_user")).isZero();
    }

    @Test
    void failsWhenThePinIsHeldByAnotherKindOfStaffMember() {
        // A cashier, a manager with another e-mail, a manager at another outlet, and a deactivated manager.
        staffRepository.save(new Staff("Cashier", pinHasher.hash(pin), StaffRole.STAFF, outletId("O1")));
        assertPinConflict();

        jdbc.execute("TRUNCATE staff RESTART IDENTITY CASCADE");
        saveManager("Manager", pin, "someone-else@bootstrap.test");
        assertPinConflict();

        jdbc.execute("TRUNCATE staff RESTART IDENTITY CASCADE");
        Staff elsewhere = new Staff("Manager", pinHasher.hash(pin), StaffRole.MANAGER, otherOutletId());
        elsewhere.setDashboardLogin("manager@bootstrap.test", passwordEncoder.encode(secret()));
        staffRepository.save(elsewhere);
        assertPinConflict();

        jdbc.execute("TRUNCATE staff RESTART IDENTITY CASCADE");
        Staff former = saveManager("Manager", pin, "manager@bootstrap.test");
        jdbc.update("UPDATE staff SET active = FALSE WHERE id = ?", former.getId());
        assertPinConflict();
    }

    @Test
    void theConfigurationNeverPrintsItsValues() {
        BootstrapProperties properties = config();

        assertThat(properties.toString()).doesNotContain(pin, adminPassword, managerPassword, "bootstrap.test");
    }

    private void assertPinConflict() {
        assertThatThrownBy(() -> run(config())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("manager-pin is already held by a different staff member")
                .hasMessageNotContaining(pin);
        assertThat(count("admin_user")).isZero();
    }

    private void run(BootstrapProperties properties) {
        run(properties, new MockEnvironment());
    }

    private void run(BootstrapProperties properties, MockEnvironment environment) {
        BootstrapInitializer initializer = new BootstrapInitializer(properties, staffRepository, adminRepository,
                pinHasher, passwordEncoder, jdbc, clock, environment);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> initializer.run(null));
    }

    private BootstrapProperties config() {
        return new BootstrapProperties(true, "O1", "Head Office Person", " Head@Bootstrap.test ", adminPassword,
                "Outlet Manager", pin, "MANAGER@bootstrap.test", managerPassword);
    }

    private BootstrapProperties withPasswords(String newAdminPassword, String newManagerPassword) {
        return new BootstrapProperties(true, "O1", "Head Office Person", "head@bootstrap.test", newAdminPassword,
                "Outlet Manager", pin, "manager@bootstrap.test", newManagerPassword);
    }

    private BootstrapProperties withAdminPassword(String password) {
        return new BootstrapProperties(true, "O1", "Head Office Person", "head@bootstrap.test", password,
                "Outlet Manager", pin, "manager@bootstrap.test", managerPassword);
    }

    private BootstrapProperties withOutlet(String outletCode) {
        return new BootstrapProperties(true, outletCode, "Head Office Person", "head@bootstrap.test", adminPassword,
                "Outlet Manager", pin, "manager@bootstrap.test", managerPassword);
    }

    private Staff saveManager(String name, String managerPin, String email) {
        Staff manager = new Staff(name, pinHasher.hash(managerPin), StaffRole.MANAGER, outletId("O1"));
        manager.setDashboardLogin(email, passwordEncoder.encode(secret()));
        return staffRepository.save(manager);
    }

    private Long outletId(String code) {
        return jdbc.queryForObject("SELECT id FROM outlet WHERE code = ?", Long.class, code);
    }

    private Long otherOutletId() {
        jdbc.update("INSERT INTO outlet (code, name) VALUES ('B1', 'Bootstrap Test Outlet') ON CONFLICT DO NOTHING");
        return outletId("B1");
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void assertNothingCreated() {
        assertThat(count("admin_user")).isZero();
        assertThat(count("staff")).isZero();
    }

    static String secret() {
        return "pw-" + UUID.randomUUID();
    }

    static String randomPin() {
        return String.format("%06d", new Random().nextInt(1_000_000));
    }

    private static String randomPinOtherThan(String other) {
        String candidate;
        do {
            candidate = randomPin();
        } while (candidate.equals(other));
        return candidate;
    }
}
