package com.counterline.pos.outlet;

import com.counterline.pos.TestcontainersConfiguration;
import com.counterline.pos.auth.PinHasher;
import com.counterline.pos.auth.Terminal;
import com.counterline.pos.common.ConflictException;
import com.counterline.pos.common.DatabaseConflicts;
import com.counterline.pos.common.ResourceNotFoundException;
import com.counterline.pos.outlet.OutletAdminService.OutletEdit;
import com.counterline.pos.outlet.OutletAdminService.OutletRequest;
import com.counterline.pos.register.RegisterService;
import com.counterline.pos.auth.Actor;
import com.counterline.pos.staff.StaffAdminService;
import com.counterline.pos.staff.StaffAdminService.AdminStaff;
import com.counterline.pos.staff.StaffAdminService.PinReset;
import com.counterline.pos.staff.StaffAdminService.StaffEdit;
import com.counterline.pos.staff.StaffAdminService.StaffRequest;
import com.counterline.pos.staff.StaffRepository;
import com.counterline.pos.staff.StaffRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Head office's changes to outlets and staff when they happen at the same time: stale edits fail with a conflict
 * instead of undoing a deactivation, a PIN reset or a session revocation; an outlet that is being deactivated is
 * not joined, reopened or registered at; an open outlet keeps a manager. Every wait is bounded.
 * Everything created (outlets with a random code, their staff) is removed afterwards.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class OutletStaffAdminConcurrencyIntegrationTest {

    private static final String PASSWORD = "pw-" + UUID.randomUUID();

    private final String suffix = UUID.randomUUID().toString().substring(0, 5);
    private final List<Long> outletIds = new ArrayList<>();
    private final Set<String> pins = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private int counter;

    private static final Actor HEAD_OFFICE = new Actor(Actor.Type.ADMIN, 1L, "Head Office");

    @Autowired
    StaffAdminService staffAdmin;
    @Autowired
    OutletAdminService outletAdmin;
    @Autowired
    OutletRepository outletRepository;
    @Autowired
    StaffRepository staffRepository;
    @Autowired
    RegisterService registerService;
    @Autowired
    PinHasher pinHasher;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager transactions;

    @AfterEach
    void removeWhatWasCreated() {
        jdbc.execute("TRUNCATE staff_audit"); // append-only: rows cannot be deleted one by one, and they hold the staff below
        for (Long outlet : outletIds) {
            jdbc.update("DELETE FROM register_session WHERE outlet_id = ?", outlet);
            jdbc.update("DELETE FROM timecard_event WHERE outlet_id = ?", outlet);
            jdbc.update("DELETE FROM staff WHERE outlet_id = ?", outlet);
            jdbc.update("DELETE FROM outlet WHERE id = ?", outlet);
        }
        jdbc.update("DELETE FROM outlet WHERE code ILIKE ?", "Q" + suffix + "%");
        jdbc.update("DELETE FROM staff WHERE email LIKE ?", "%-" + suffix + "@%");
    }

    // ---- Stale edits never undo a newer change ----

    @Test
    void aStaleEditNeverReactivatesSomeoneWhoWasDeactivatedNorRestoresTheirSessions() {
        long outlet = outlet();
        manager(outlet);
        AdminStaff cashier = cashier(outlet);

        Throwable failure = staleStaff(cashier.id(), () -> staffAdmin.deactivate(HEAD_OFFICE, cashier.id()),
                () -> staffAdmin.update(HEAD_OFFICE, cashier.id(), new StaffEdit("Renamed", StaffRole.STAFF, outlet, null, null, versionOf(cashier.id()))));

        assertThat(failure).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(jdbc.queryForObject("SELECT active FROM staff WHERE id = ?", Boolean.class, cashier.id())).isFalse();
        assertThat(jdbc.queryForObject("SELECT full_name FROM staff WHERE id = ?", String.class, cashier.id())).isEqualTo(cashier.fullName());
        assertThat(jdbc.queryForObject("SELECT auth_session_version FROM staff WHERE id = ?", Integer.class, cashier.id())).isEqualTo(1);
    }

    @Test
    void aStaleEditNeverRestoresAPinThatWasReset() {
        long outlet = outlet();
        manager(outlet);
        AdminStaff cashier = cashier(outlet);
        String newPin = pin();

        Throwable failure = staleStaff(cashier.id(), () -> staffAdmin.resetPin(HEAD_OFFICE, cashier.id(), new PinReset(newPin)),
                () -> staffAdmin.update(HEAD_OFFICE, cashier.id(), new StaffEdit("Renamed", StaffRole.STAFF, outlet, null, null, versionOf(cashier.id()))));

        assertThat(failure).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(jdbc.queryForObject("SELECT pin_hmac FROM staff WHERE id = ?", String.class, cashier.id()))
                .isEqualTo(pinHasher.hash(newPin));
        assertThat(jdbc.queryForObject("SELECT auth_session_version FROM staff WHERE id = ?", Integer.class, cashier.id())).isEqualTo(1);
    }

    @Test
    void aStaleDeactivationNeverUndoesARoleOrOutletChange() {
        long outlet = outlet();
        long other = outlet();
        manager(outlet);
        manager(other);
        AdminStaff cashier = cashier(outlet);

        Throwable failure = staleStaff(cashier.id(),
                () -> staffAdmin.update(HEAD_OFFICE, cashier.id(), new StaffEdit(cashier.fullName(), StaffRole.STAFF, other, null, null, versionOf(cashier.id()))),
                () -> staffAdmin.deactivate(HEAD_OFFICE, cashier.id()));

        assertThat(failure).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(jdbc.queryForObject("SELECT outlet_id FROM staff WHERE id = ?", Long.class, cashier.id())).isEqualTo(other);
        assertThat(jdbc.queryForObject("SELECT active FROM staff WHERE id = ?", Boolean.class, cashier.id())).isTrue();
    }

    @Test
    void aStaleOutletEditNeverReopensAnOutletThatWasDeactivated() {
        long outlet = outlet();

        Throwable failure = staleOutlet(outlet, () -> outletAdmin.deactivate(outlet),
                () -> outletAdmin.update(outlet, new OutletEdit("Renamed " + suffix, null)));

        assertThat(failure).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(jdbc.queryForObject("SELECT active FROM outlet WHERE id = ?", Boolean.class, outlet)).isFalse();
        assertThat(jdbc.queryForObject("SELECT name FROM outlet WHERE id = ?", String.class, outlet)).doesNotStartWith("Renamed");
    }

    @Test
    void closingAnOutletRaisesEachStaffVersionSoAnEarlierEditCannotRestoreTheirSessions() {
        long outlet = outlet();
        manager(outlet);
        AdminStaff cashier = cashier(outlet);
        long before = jdbc.queryForObject("SELECT version FROM staff WHERE id = ?", Long.class, cashier.id());

        Throwable failure = staleStaff(cashier.id(), () -> outletAdmin.deactivate(outlet),
                () -> staffAdmin.update(HEAD_OFFICE, cashier.id(), new StaffEdit("Renamed", StaffRole.STAFF, outlet, null, null, versionOf(cashier.id()))));

        assertThat(failure).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(jdbc.queryForObject("SELECT version FROM staff WHERE id = ?", Long.class, cashier.id())).isGreaterThan(before);
        assertThat(jdbc.queryForObject("SELECT auth_session_version FROM staff WHERE id = ?", Integer.class, cashier.id())).isEqualTo(1);
    }

    // ---- Duplicates created at the same moment ----

    @RepeatedTest(3)
    void twoCreatesWithTheSamePinLeaveOneAndAClearConflict() {
        long outlet = outlet();
        String pin = pin();

        List<Throwable> failures = both(
                () -> staffAdmin.create(HEAD_OFFICE, new StaffRequest("First " + suffix, StaffRole.STAFF, outlet, pin, null, null)),
                () -> staffAdmin.create(HEAD_OFFICE, new StaffRequest("Second " + suffix, StaffRole.STAFF, outlet, pin, null, null)));

        assertOneConflict(failures, "PIN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff WHERE pin_hmac = ?", Integer.class, pinHasher.hash(pin))).isEqualTo(1);
    }

    @RepeatedTest(3)
    void twoCreatesWithTheSameDashboardEmailLeaveOneAndAClearConflict() {
        long outlet = outlet();
        String email = "boss-" + suffix + "@shop.test";

        List<Throwable> failures = both(
                () -> staffAdmin.create(HEAD_OFFICE, new StaffRequest("First " + suffix, StaffRole.MANAGER, outlet, pin(), email, PASSWORD)),
                () -> staffAdmin.create(HEAD_OFFICE, new StaffRequest("Second " + suffix, StaffRole.MANAGER, outlet, pin(), email, PASSWORD)));

        assertOneConflict(failures, "e-mail");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff WHERE email = ?", Integer.class, email)).isEqualTo(1);
    }

    @RepeatedTest(3)
    void twoOutletsWithTheSameCodeLeaveOneAndAClearConflict() {
        String code = code();

        List<Throwable> failures = both(
                () -> outletAdmin.create(new OutletRequest(code, "First " + suffix + counter, null)),
                () -> outletAdmin.create(new OutletRequest(code, "Second " + suffix + counter, null)));

        assertOneConflict(failures, "code");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outlet WHERE code = ?", Integer.class, code.toUpperCase())).isEqualTo(1);
    }

    @RepeatedTest(3)
    void twoOutletsWhoseNamesDifferOnlyInCaseLeaveOneAndAClearConflict() {
        String name = "Cafe " + suffix + ++counter;
        String firstCode = code();
        String secondCode = code();

        List<Throwable> failures = both(
                () -> outletAdmin.create(new OutletRequest(firstCode, name, null)),
                () -> outletAdmin.create(new OutletRequest(secondCode, name.toUpperCase(), null)));

        assertOneConflict(failures, "name");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outlet WHERE lower(name) = ?", Integer.class, name.toLowerCase())).isEqualTo(1);
    }

    @Test
    void theDatabaseItselfRefusesOutletNamesThatDifferOnlyInCase() {
        long outlet = outlet();
        String name = jdbc.queryForObject("SELECT name FROM outlet WHERE id = ?", String.class, outlet);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO outlet (code, name) VALUES (?, ?)", code(), name.toUpperCase()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(DatabaseConflicts.violates(e, "outlet_name_lower_uq")).isTrue());
    }

    // ---- An open outlet keeps a manager ----

    @Test
    void anOpenOutletKeepsItsLastActiveManagerWhateverRemovesThem() {
        long outlet = outlet();
        long elsewhere = outlet();
        manager(elsewhere);
        AdminStaff first = manager(outlet);
        AdminStaff cashier = cashier(outlet);

        assertThatThrownBy(() -> staffAdmin.deactivate(HEAD_OFFICE, first.id())).isInstanceOf(ConflictException.class)
                .hasMessageContaining("only active manager");
        assertThatThrownBy(() -> staffAdmin.update(HEAD_OFFICE, first.id(), new StaffEdit(first.fullName(), StaffRole.STAFF, outlet, null, null, versionOf(first.id()))))
                .isInstanceOf(ConflictException.class).hasMessageContaining("only active manager");
        assertThatThrownBy(() -> staffAdmin.update(HEAD_OFFICE, first.id(), new StaffEdit(first.fullName(), StaffRole.MANAGER, elsewhere, null, null, versionOf(first.id()))))
                .isInstanceOf(ConflictException.class).hasMessageContaining("only active manager");
        assertThat(jdbc.queryForObject("SELECT active AND role = 'MANAGER' AND outlet_id = ? FROM staff WHERE id = ?",
                Boolean.class, outlet, first.id())).isTrue();

        // Cashiers are not held to it; a second manager frees the first, and then the second is the last.
        staffAdmin.deactivate(HEAD_OFFICE, cashier.id());
        AdminStaff second = manager(outlet);
        staffAdmin.deactivate(HEAD_OFFICE, first.id());
        assertThatThrownBy(() -> staffAdmin.deactivate(HEAD_OFFICE, second.id())).isInstanceOf(ConflictException.class);
        staffAdmin.reactivate(HEAD_OFFICE, first.id());
        staffAdmin.deactivate(HEAD_OFFICE, second.id());
        assertThat(activeManagers(outlet)).isEqualTo(1);
    }

    @Test
    void aClosedOutletCanBeWoundDownAndBroughtBackWithoutAManager() {
        long outlet = outlet();
        AdminStaff only = manager(outlet);

        outletAdmin.deactivate(outlet); // never refused for want of a manager
        staffAdmin.deactivate(HEAD_OFFICE, only.id()); // a closed outlet is not held to the rule
        outletAdmin.reactivate(outlet);
        assertThat(activeManagers(outlet)).isZero();

        // Recovery: a new manager can be added, or the old one reactivated.
        manager(outlet);
        staffAdmin.reactivate(HEAD_OFFICE, only.id());
        assertThat(activeManagers(outlet)).isEqualTo(2);
    }

    @RepeatedTest(3)
    void twoManagersOfOneOutletCannotBothBeRemovedAtOnce() {
        long outlet = outlet();
        AdminStaff first = manager(outlet);
        AdminStaff second = manager(outlet);

        List<Throwable> failures = both(() -> staffAdmin.deactivate(HEAD_OFFICE, first.id()), () -> staffAdmin.deactivate(HEAD_OFFICE, second.id()));

        assertOneConflict(failures, "only active manager");
        assertThat(activeManagers(outlet)).isEqualTo(1);
    }

    @RepeatedTest(3)
    void aDeactivationAndADemotionOfTheTwoManagersCannotBothSucceed() {
        long outlet = outlet();
        AdminStaff first = manager(outlet);
        AdminStaff second = manager(outlet);

        List<Throwable> failures = both(() -> staffAdmin.deactivate(HEAD_OFFICE, first.id()),
                () -> staffAdmin.update(HEAD_OFFICE, second.id(), new StaffEdit(second.fullName(), StaffRole.STAFF, outlet, null, null, versionOf(second.id()))));

        assertThat(failures).singleElement().isInstanceOf(ConflictException.class);
        assertThat(activeManagers(outlet)).isEqualTo(1);
    }

    @Test
    void managersMovingInOppositeDirectionsAtOnceNeverDeadlock() {
        long a = outlet();
        long b = outlet();
        AdminStaff a1 = manager(a);
        manager(a);
        AdminStaff b1 = manager(b);
        manager(b);

        for (int round = 0; round < 5; round++) {
            List<Throwable> there = both(
                    () -> staffAdmin.update(HEAD_OFFICE, a1.id(), new StaffEdit(a1.fullName(), StaffRole.MANAGER, b, null, null, versionOf(a1.id()))),
                    () -> staffAdmin.update(HEAD_OFFICE, b1.id(), new StaffEdit(b1.fullName(), StaffRole.MANAGER, a, null, null, versionOf(b1.id()))));
            List<Throwable> back = both(
                    () -> staffAdmin.update(HEAD_OFFICE, a1.id(), new StaffEdit(a1.fullName(), StaffRole.MANAGER, a, null, null, versionOf(a1.id()))),
                    () -> staffAdmin.update(HEAD_OFFICE, b1.id(), new StaffEdit(b1.fullName(), StaffRole.MANAGER, b, null, null, versionOf(b1.id()))));
            assertThat(there).isEmpty();
            assertThat(back).isEmpty();
        }
    }

    // ---- An outlet being deactivated is not joined, reopened or registered at ----

    @Test
    void aStaffMemberCannotBeCreatedAtAnOutletThatIsBeingDeactivated() {
        long outlet = outlet();

        Throwable failure = whileBeingDeactivated(outlet,
                () -> staffAdmin.create(HEAD_OFFICE, new StaffRequest("Late " + suffix, StaffRole.STAFF, outlet, pin(), null, null)));

        assertThat(failure).isInstanceOf(ResourceNotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff WHERE outlet_id = ?", Integer.class, outlet)).isZero();
    }

    @Test
    void aStaffMemberCannotBeMovedToAnOutletThatIsBeingDeactivated() {
        long from = outlet();
        long to = outlet();
        manager(from);
        AdminStaff cashier = cashier(from);

        Throwable failure = whileBeingDeactivated(to,
                () -> staffAdmin.update(HEAD_OFFICE, cashier.id(), new StaffEdit(cashier.fullName(), StaffRole.STAFF, to, null, null, versionOf(cashier.id()))));

        assertThat(failure).isInstanceOf(ResourceNotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT outlet_id FROM staff WHERE id = ?", Long.class, cashier.id())).isEqualTo(from);
    }

    @Test
    void aStaffMemberCannotBeReactivatedAtAnOutletThatIsBeingDeactivated() {
        long outlet = outlet();
        manager(outlet);
        AdminStaff cashier = cashier(outlet);
        staffAdmin.deactivate(HEAD_OFFICE, cashier.id());

        Throwable failure = whileBeingDeactivated(outlet, () -> staffAdmin.reactivate(HEAD_OFFICE, cashier.id()));

        assertThat(failure).isInstanceOf(ConflictException.class).hasMessageContaining("Reactivate the staff member's outlet first");
        assertThat(jdbc.queryForObject("SELECT active FROM staff WHERE id = ?", Boolean.class, cashier.id())).isFalse();
    }

    @Test
    void aRegisterCannotBeOpenedAtAnOutletThatIsBeingDeactivated() {
        long outlet = outlet();
        manager(outlet);
        AdminStaff cashier = cashier(outlet);

        Throwable failure = whileBeingDeactivated(outlet, () -> registerService.open(null, new Terminal(cashier.id(), outlet)));

        assertThat(failure).isInstanceOf(ResourceNotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM register_session WHERE outlet_id = ?", Integer.class, outlet)).isZero();
    }

    @Test
    void anOutletDeactivationWaitsForAStaffCreationInProgressAndThenEndsTheNewStaffsSessions() throws Exception {
        long outlet = outlet();
        manager(outlet);
        AdminStaff[] created = new AdminStaff[1];

        // The creation holds the outlet open (not yet committed); the deactivation queues behind it.
        runBehindALock(() -> created[0] = staffAdmin.create(HEAD_OFFICE, new StaffRequest("New " + suffix, StaffRole.STAFF, outlet, pin(), null, null)),
                () -> outletAdmin.deactivate(outlet), "%from outlet%for%update%");

        assertThat(jdbc.queryForObject("SELECT active FROM outlet WHERE id = ?", Boolean.class, outlet)).isFalse();
        assertThat(jdbc.queryForObject("SELECT auth_session_version FROM staff WHERE id = ?", Integer.class, created[0].id()))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void anOutletWithARegisterBeingOpenedCannotBeDeactivated() throws Exception {
        long outlet = outlet();
        manager(outlet);
        AdminStaff cashier = cashier(outlet);

        Throwable failure = runBehindALock(() -> registerService.open(null, new Terminal(cashier.id(), outlet)),
                () -> outletAdmin.deactivate(outlet), "%from outlet%for%update%");

        assertThat(failure).isInstanceOf(ConflictException.class).hasMessageContaining("open register");
        assertThat(jdbc.queryForObject("SELECT active FROM outlet WHERE id = ?", Boolean.class, outlet)).isTrue();
    }

    // ---- helpers ----

    private long outlet() {
        long id = outletAdmin.create(new OutletRequest(code(), "Shop " + suffix + ++counter, null)).id();
        outletIds.add(id);
        return id;
    }

    /** A code of at most ten characters, unique to this run. */
    private String code() {
        return "Q" + suffix + (++counter);
    }

    private AdminStaff manager(long outlet) {
        return staffAdmin.create(HEAD_OFFICE, new StaffRequest("Manager " + suffix + ++counter, StaffRole.MANAGER, outlet, pin(), null, null));
    }

    private AdminStaff cashier(long outlet) {
        return staffAdmin.create(HEAD_OFFICE, new StaffRequest("Cashier " + suffix + ++counter, StaffRole.STAFF, outlet, pin(), null, null));
    }

    private String pin() {
        String pin;
        do {
            pin = "%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000));
        } while (!pins.add(pin));
        return pin;
    }

    /** Exactly one of the two failed, with a 409 whose message names the clash. */
    private static void assertOneConflict(List<Throwable> failures, String fragment) {
        assertThat(failures).singleElement().isInstanceOf(ConflictException.class);
        assertThat(failures.get(0).getMessage()).contains(fragment);
    }

    private int activeManagers(long outlet) {
        return jdbc.queryForObject("SELECT count(*) FROM staff WHERE outlet_id = ? AND role = 'MANAGER' AND active", Integer.class, outlet);
    }

    /**
     * Reads the person first (inside the transaction, so the service finds that copy), lets another request change and
     * commit them, then runs the action on the stale copy. Returns what it threw.
     */
    private Throwable staleStaff(Long staffId, Runnable concurrent, Runnable stale) {
        return stale(() -> staffRepository.findById(staffId).orElseThrow(), concurrent, stale);
    }

    private Throwable staleOutlet(Long outletId, Runnable concurrent, Runnable stale) {
        return stale(() -> outletRepository.findById(outletId).orElseThrow(), concurrent, stale);
    }

    private Throwable stale(Runnable read, Runnable concurrent, Runnable stale) {
        try {
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                read.run();
                inAnotherThread(concurrent);
                stale.run();
            });
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static void inAnotherThread(Runnable action) {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(action).get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("The concurrent change failed", e.getCause());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Runs both at the same moment; returns what each threw (nothing for one that succeeded). */
    private List<Throwable> both(Runnable first, Runnable second) {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> outcomes = new ArrayList<>();
            for (Runnable action : List.of(first, second)) {
                outcomes.add(pool.submit(() -> {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    action.run();
                    return null;
                }));
            }
            start.countDown();
            List<Throwable> failures = new ArrayList<>();
            for (Future<?> outcome : outcomes) {
                try {
                    outcome.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
            return failures;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Deactivates the outlet and keeps that transaction open (uncommitted) while {@code action} is started; the
     * deactivation commits once the action is queued behind the outlet's lock. Returns what the action threw.
     */
    private Throwable whileBeingDeactivated(long outlet, Runnable action) {
        try {
            return runBehindALock(() -> outletAdmin.deactivate(outlet), action, "%from outlet%for share%");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Runs {@code holder} in a transaction that stays open until {@code action} is waiting for a lock whose statement
     * matches {@code waitingSql} (a LIKE pattern), then commits it. Returns what the action threw, or null.
     */
    private Throwable runBehindALock(Runnable holder, Runnable action, String waitingSql) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holding = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                holder.run();
                held.countDown();
                try {
                    assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();
            Future<?> acting = pool.submit(action);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query ILIKE ?",
                    Integer.class, waitingSql) < 1) {
                assertThat(System.nanoTime()).as("the action never queued behind the lock").isLessThan(deadline);
                Thread.sleep(25);
            }
            release.countDown();
            holding.get(20, TimeUnit.SECONDS);
            try {
                acting.get(20, TimeUnit.SECONDS);
                return null;
            } catch (ExecutionException e) {
                return e.getCause();
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** The version a form opened now would carry: these edits are about locks and rules, not stale forms. */
    private long versionOf(Long staffId) {
        return jdbc.queryForObject("SELECT version FROM staff WHERE id = ?", Long.class, staffId);
    }
}
