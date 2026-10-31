package com.counterline.pos.timecards;

import com.counterline.pos.TestcontainersConfiguration;
import com.counterline.pos.auth.Actor;
import com.counterline.pos.auth.DashboardUser;
import com.counterline.pos.auth.PinHasher;
import com.counterline.pos.outlet.OutletAdminService;
import com.counterline.pos.staff.Staff;
import com.counterline.pos.staff.StaffRepository;
import com.counterline.pos.staff.StaffRole;
import com.counterline.pos.timecards.TimecardCorrectionService.CloseShift;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An open shift belongs to the outlet where its IN was made, and is closed by the person or by a
 * correction (an extra OUT with who and why; the IN is never touched). Outlet days are in
 * Asia/Kuala_Lumpur (UTC+8): 12 Oct runs from 11 Oct 16:00Z to 12 Oct 16:00Z.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Sql("/catalog-fixture.sql")
class TimecardLifecycleIntegrationTest {

    private static final String STAFF = "/api/v1/dashboard/admin/staff";
    private static final String OUTLETS = "/api/v1/dashboard/admin/outlets";
    /** 23:00 on 12 Oct, local. */
    private static final String LATE_12TH = "2026-10-12T15:00:00Z";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffRepository staffRepository;

    @Autowired
    PinHasher pinHasher;

    @Autowired
    TimecardService timecards;

    @Autowired
    TimecardCorrectionService corrections;

    @Autowired
    OutletAdminService outletAdmin;

    @Autowired
    PlatformTransactionManager transactions;

    private long second;
    private Long cashierId;
    private Long managerId;
    private Long secondManagerId;

    /** Outlets are shared with the other test classes: leave them all open. */
    @AfterEach
    void reopenOutlets() {
        jdbc.update("UPDATE outlet SET active = TRUE");
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE login_throttle, staff, customer RESTART IDENTITY CASCADE"); // also clock events
        jdbc.update("INSERT INTO outlet (code, name) VALUES ('O2', 'Outlet 2') ON CONFLICT (code) DO UPDATE SET active = TRUE");
        second = jdbc.queryForObject("SELECT id FROM outlet WHERE code = 'O2'", Long.class);
        cashierId = staffRepository.save(new Staff("Test Cashier", pinHasher.hash("123456"), StaffRole.STAFF, 1L)).getId();
        managerId = staffRepository.save(new Staff("Test Manager", pinHasher.hash("654321"), StaffRole.MANAGER, 1L)).getId();
        secondManagerId = staffRepository.save(new Staff("Second Manager", pinHasher.hash("888888"), StaffRole.MANAGER, second)).getId();
    }

    // ---- An open shift keeps the person and the outlet where they are ----

    @Test
    void aClockedInStaffMemberCannotBeMovedOrDeactivatedAndTheirOutletCannotBeClosedUntilTheShiftEnds() throws Exception {
        timecards.clock("123456", TimecardEvent.Type.IN, 1L, "10.0.0.1");

        String move = "{\"fullName\":\"Test Cashier\",\"role\":\"STAFF\",\"outletId\":" + second + "}";
        call(put(STAFF + "/" + cashierId), admin(), move).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Test Cashier is clocked in. Close the shift (Reports, Timecards) before moving them to another outlet."));
        call(post(STAFF + "/" + cashierId + "/deactivate"), admin(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Test Cashier is clocked in. Close the shift (Reports, Timecards) before deactivating them."));
        call(post(OUTLETS + "/1/deactivate"), admin(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("has staff clocked in")));
        assertThat(jdbc.queryForObject("SELECT outlet_id FROM staff WHERE id = ?", Long.class, cashierId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT active FROM staff WHERE id = ?", Boolean.class, cashierId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT active FROM outlet WHERE id = 1", Boolean.class)).isTrue();

        // Staying put is fine: a rename, with the shift still open.
        call(put(STAFF + "/" + cashierId), admin(), "{\"fullName\":\"Test Cashier B\",\"role\":\"STAFF\",\"outletId\":1}")
                .andExpect(status().isOk());

        // Closed (here by the person), nothing is in the way.
        timecards.clock("123456", TimecardEvent.Type.OUT, 1L, "10.0.0.1");
        call(put(STAFF + "/" + cashierId), admin(), move.replace("Test Cashier", "Test Cashier B")).andExpect(status().isOk());
        call(post(STAFF + "/" + cashierId + "/deactivate"), admin(), null).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT outlet_id FROM staff WHERE id = ?", Long.class, cashierId)).isEqualTo(second);
    }

    @Test
    void anOutletWithAnOpenShiftCannotBeDeactivatedButAnotherCan() throws Exception {
        event(secondManagerId, "IN", "2026-10-12T02:00:00Z", second);
        call(post(OUTLETS + "/" + second + "/deactivate"), admin(), null).andExpect(status().isConflict());
        // The first outlet has nobody clocked in (its staff are not): it can be closed.
        call(post(OUTLETS + "/1/deactivate"), admin(), null).andExpect(status().isOk());
        call(post(OUTLETS + "/1/reactivate"), admin(), null).andExpect(status().isOk());
    }

    // ---- The correction ----

    @Test
    void aCorrectionClosesTheShiftWithAnAuditedOutAndLeavesTheInAlone() throws Exception {
        event(cashierId, "IN", "2026-10-12T02:00:00Z", 1);
        Long inId = jdbc.queryForObject("SELECT id FROM timecard_event WHERE type = 'IN'", Long.class);

        call(post(close(1, cashierId)), manager(1, managerId, "Test Manager"), closeBody("2026-10-12T10:00:00Z", "  Forgot to clock out  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staffName").value("Test Cashier"))
                .andExpect(jsonPath("$.inAt").value("2026-10-12T02:00:00Z"))
                .andExpect(jsonPath("$.outAt").value("2026-10-12T10:00:00Z"))
                .andExpect(jsonPath("$.correctedBy").value("Test Manager"))
                .andExpect(jsonPath("$.reason").value("Forgot to clock out"));

        // The IN is untouched; the OUT is a new row that says who, why and when it was entered.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM timecard_event", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForMap("SELECT type, occurred_at, corrected_by FROM timecard_event WHERE id = ?", inId))
                .containsEntry("corrected_by", null);
        assertThat(jdbc.queryForMap("SELECT outlet_id, occurred_at, corrected_by, corrected_reason, corrected_at FROM timecard_event WHERE type = 'OUT'"))
                .containsEntry("outlet_id", 1L).containsEntry("corrected_by", "Test Manager")
                .containsEntry("corrected_reason", "Forgot to clock out")
                .satisfies(row -> assertThat(row.get("corrected_at")).isNotNull());

        // The reports show it, on the till as well as the dashboard.
        call(get("/api/v1/reports/timecards").param("from", "2026-10-12").param("to", "2026-10-12"), cashier(1), null)
                .andExpect(jsonPath("$.entries[*].type").value(contains("IN", "OUT")))
                .andExpect(jsonPath("$.entries[0].correctedBy").doesNotExist())
                .andExpect(jsonPath("$.entries[1].correctedBy").value("Test Manager"))
                .andExpect(jsonPath("$.entries[1].correctedReason").value("Forgot to clock out"))
                .andExpect(jsonPath("$.openShifts", hasSize(0)));

        // Closed once: a second correction, or the person's own Clock Out, has nothing to close.
        call(post(close(1, cashierId)), admin(), closeBody("2026-10-12T11:00:00Z", "Again")).andExpect(status().isConflict());
        org.junit.jupiter.api.Assertions.assertThrows(com.counterline.pos.common.BusinessRuleException.class,
                () -> timecards.clock("123456", TimecardEvent.Type.OUT, 1L, "10.0.0.1"));
        timecards.clock("123456", TimecardEvent.Type.IN, 1L, "10.0.0.1");
    }

    @Test
    void aCorrectionIsHeadOfficesForAnyOutletAndAManagersForTheirOwn() throws Exception {
        event(cashierId, "IN", "2026-10-12T02:00:00Z", 1);
        event(secondManagerId, "IN", "2026-10-12T02:00:00Z", second);

        // A cashier is not a dashboard user.
        call(post(close(1, cashierId)), cashier(1), closeBody("2026-10-12T10:00:00Z", "Mine")).andExpect(status().isForbidden());
        // Another outlet's manager is told there is no such outlet; nothing changes.
        call(post(close(1, cashierId)), manager(second, secondManagerId, "Second Manager"), closeBody("2026-10-12T10:00:00Z", "Not mine"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM timecard_event WHERE type = 'OUT'", Long.class)).isZero();

        // A shift is closed at the outlet it was started at, not another's.
        call(post(close(second, cashierId)), admin(), closeBody("2026-10-12T10:00:00Z", "Wrong outlet")).andExpect(status().isConflict());

        // Head office closes either; a manager their own.
        call(post(close(second, secondManagerId)), admin(), closeBody("2026-10-12T10:00:00Z", "Head office")).andExpect(status().isOk())
                .andExpect(jsonPath("$.correctedBy").value("Head Office"));
        call(post(close(1, cashierId)), manager(1, managerId, "Test Manager"), closeBody("2026-10-12T10:00:00Z", "Own outlet"))
                .andExpect(status().isOk());
    }

    @Test
    void aCorrectionNeedsAReasonAndAnOutBetweenTheInAndNow() throws Exception {
        event(cashierId, "IN", "2026-10-12T02:00:00Z", 1);
        String path = close(1, cashierId);

        call(post(path), admin(), "{\"reason\":\"No time\"}").andExpect(status().isBadRequest());
        call(post(path), admin(), "{\"outAt\":\"2026-10-12T10:00:00Z\"}").andExpect(status().isBadRequest());
        call(post(path), admin(), closeBody("2026-10-12T10:00:00Z", "   ")).andExpect(status().isBadRequest());
        call(post(path), admin(), closeBody("2026-10-12T10:00:00Z", "x".repeat(201))).andExpect(status().isBadRequest());
        call(post(path), admin(), closeBody("2026-10-12T01:59:59Z", "Before the IN")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The clock-out cannot be before the clock-in"));
        call(post(path), admin(), closeBody(Instant.now().plusSeconds(3600).toString(), "In the future")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The clock-out cannot be in the future"));
        // Nobody by that id is answered like anyone else without an open shift here (see the enumeration test).
        call(post("/api/v1/dashboard/outlets/1/timecards/999999/close"), admin(), closeBody("2026-10-12T10:00:00Z", "No such person"))
                .andExpect(status().isConflict());
        call(post(close(1, managerId)), admin(), closeBody("2026-10-12T10:00:00Z", "Not clocked in")).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM timecard_event WHERE type = 'OUT'", Long.class)).isZero();

        // Exactly the IN's instant is the shortest shift there can be.
        call(post(path), admin(), closeBody("2026-10-12T02:00:00Z", "Zero minutes")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM timecard_event WHERE type = 'OUT'", Long.class)).isEqualTo(1L);
        // The person can clock in again straight away: the OUT is the later event, not the IN.
        timecards.clock("123456", TimecardEvent.Type.IN, 1L, "10.0.0.1");
    }

    // ---- Open, stale and cross-midnight shifts in the report ----

    @Test
    void anOpenShiftStaysListedWhateverTheDatesAndIsStaleOnceItIsTooLong() throws Exception {
        event(cashierId, "IN", LATE_12TH, 1);
        event(managerId, "IN", Instant.now().minusSeconds(3600).toString(), 1);

        // Whatever period is asked for, and from either side.
        for (String uri : List.of("/api/v1/reports/timecards", "/api/v1/dashboard/outlets/1/reports/timecards")) {
            call(get(uri).param("from", "2026-10-20").param("to", "2026-10-20"), uri.contains("dashboard") ? admin() : cashier(1), null)
                    .andExpect(jsonPath("$.entries", hasSize(0)))
                    .andExpect(jsonPath("$.openShifts[*].staffName").value(contains("Test Cashier", "Test Manager")))
                    .andExpect(jsonPath("$.openShifts[0].staffId").value(cashierId.intValue()))
                    .andExpect(jsonPath("$.openShifts[0].since").value(LATE_12TH))
                    .andExpect(jsonPath("$.openShifts[0].stale").value(true))
                    .andExpect(jsonPath("$.openShifts[1].stale").value(false));
        }
    }

    @Test
    void aShiftAcrossLocalMidnightIsReportedOnTheDaysItsEventsHappened() throws Exception {
        event(cashierId, "IN", LATE_12TH, 1);                 // 12 Oct 23:00
        call(post(close(1, cashierId)), admin(), closeBody("2026-10-12T17:00:00Z", "Left after midnight")).andExpect(status().isOk()); // 13 Oct 01:00

        report("2026-10-12").andExpect(jsonPath("$.entries[*].type").value(contains("IN")));
        report("2026-10-13").andExpect(jsonPath("$.entries[*].type").value(contains("OUT")))
                .andExpect(jsonPath("$.entries[0].correctedBy").value("Head Office"));
        report("2026-10-12", "2026-10-13").andExpect(jsonPath("$.entries[*].type").value(contains("IN", "OUT")));
    }

    @Test
    void theLocalDayRunsFromMidnightInclusiveToTheNextMidnightExclusive() throws Exception {
        event(cashierId, "IN", "2026-10-11T15:59:59Z", 1);    // 11 Oct 23:59:59
        event(cashierId, "OUT", "2026-10-11T16:00:00Z", 1);   // 12 Oct 00:00:00
        event(cashierId, "IN", "2026-10-12T15:59:59Z", 1);    // 12 Oct 23:59:59
        event(cashierId, "OUT", "2026-10-12T16:00:00Z", 1);   // 13 Oct 00:00:00

        report("2026-10-11").andExpect(jsonPath("$.entries[*].at").value(contains("2026-10-11T15:59:59Z")));
        report("2026-10-12").andExpect(jsonPath("$.entries[*].at")
                .value(contains("2026-10-11T16:00:00Z", "2026-10-12T15:59:59Z")));
        report("2026-10-13").andExpect(jsonPath("$.entries[*].at").value(contains("2026-10-12T16:00:00Z")));
    }

    // ---- Outlet isolation, moved and deactivated staff ----

    @Test
    void aShiftIsTheOutletsItWasStartedAtEvenIfThePersonWorksElsewhereNowAndCanBeClosedWhenInactive() throws Exception {
        // Left open before the guards existed: started at outlet 1, the person now works at the second outlet and is inactive.
        event(cashierId, "IN", "2026-10-12T02:00:00Z", 1);
        jdbc.update("UPDATE staff SET outlet_id = ?, active = FALSE WHERE id = ?", second, cashierId);

        call(get("/api/v1/dashboard/outlets/" + second + "/reports/timecards").param("from", "2026-10-12").param("to", "2026-10-12"),
                manager(second, secondManagerId, "Second Manager"), null)
                .andExpect(jsonPath("$.entries", hasSize(0))).andExpect(jsonPath("$.openShifts", hasSize(0)));
        call(get("/api/v1/dashboard/outlets/1/reports/timecards").param("from", "2026-10-12").param("to", "2026-10-12"),
                manager(second, secondManagerId, "Second Manager"), null).andExpect(status().isNotFound());
        call(get("/api/v1/dashboard/outlets/1/reports/timecards").param("from", "2026-10-12").param("to", "2026-10-12"),
                manager(1, managerId, "Test Manager"), null).andExpect(jsonPath("$.openShifts[0].staffName").value("Test Cashier"));

        // Only the outlet it belongs to (or head office) can close it; the inactive person is no obstacle.
        call(post(close(second, cashierId)), manager(second, secondManagerId, "Second Manager"), closeBody("2026-10-12T10:00:00Z", "Mine?"))
                .andExpect(status().isConflict());
        call(post(close(1, cashierId)), manager(1, managerId, "Test Manager"), closeBody("2026-10-12T10:00:00Z", "Cleaning up"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT outlet_id FROM timecard_event WHERE type = 'OUT'", Long.class)).isEqualTo(1L);
    }

    // ---- Concurrent closure ----

    @Test
    void aClockOutAndACorrectionAtTheSameTimeLeaveExactlyOneOut() throws Exception {
        event(cashierId, "IN", "2026-10-12T02:00:00Z", 1);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> self = pool.submit(() -> {
                go.await();
                return timecards.clock("123456", TimecardEvent.Type.OUT, 1L, "10.0.0.1");
            });
            Future<?> correction = pool.submit(() -> {
                go.await();
                return corrections.close(new DashboardUser(true, null), 1L, cashierId,
                        new CloseShift(Instant.parse("2026-10-12T10:00:00Z"), null, "Closing it"),
                        new Actor(Actor.Type.ADMIN, 1L, "Head Office"));
            });
            go.countDown();
            List<Throwable> failures = new ArrayList<>();
            for (Future<?> result : List.of(self, correction)) {
                try {
                    result.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
            assertThat(failures).hasSize(1);
            assertThat(failures.getFirst()).isInstanceOfAny(com.counterline.pos.common.BusinessRuleException.class,
                    com.counterline.pos.common.ConflictException.class);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForList("SELECT type FROM timecard_event ORDER BY id", String.class)).containsExactly("IN", "OUT");
    }

    // ---- Clock In and an outlet's deactivation never leave an inactive outlet with a new open shift ----

    @Test
    void ifTheClockInWinsTheDeactivationWaitsForItThenIsRefused() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // The clock-in's transaction is still open when the deactivation starts: it must wait for it.
            Future<?> deactivation = new TransactionTemplate(transactions).execute(status -> {
                timecards.recordClock(secondManagerId, pinHasher.hash("888888"), TimecardEvent.Type.IN, second);
                Future<?> started = pool.submit(() -> outletAdmin.deactivate(second));
                assertStillWaiting(started);
                return started;
            });
            ExecutionException refused = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                    () -> deactivation.get(10, TimeUnit.SECONDS));
            assertThat(refused.getCause()).isInstanceOf(com.counterline.pos.common.ConflictException.class)
                    .hasMessageContaining("has staff clocked in");
        } finally {
            pool.shutdownNow();
        }
        assertOutletNeverClosedWithAnOpenShift();
        assertThat(jdbc.queryForObject("SELECT active FROM outlet WHERE id = ?", Boolean.class, second)).isTrue();
        assertThat(openShifts()).isEqualTo(1L);
    }

    @Test
    void ifTheDeactivationWinsTheClockInWaitsForItThenIsRefused() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> clockIn = new TransactionTemplate(transactions).execute(status -> {
                outletAdmin.deactivate(second);
                Future<?> started = pool.submit(() -> timecards.clock("888888", TimecardEvent.Type.IN, second, "10.0.0.2"));
                assertStillWaiting(started);
                return started;
            });
            ExecutionException refused = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                    () -> clockIn.get(10, TimeUnit.SECONDS));
            assertThat(refused.getCause()).isInstanceOf(com.counterline.pos.common.BusinessRuleException.class)
                    .hasMessage("This outlet is closed");
        } finally {
            pool.shutdownNow();
        }
        assertOutletNeverClosedWithAnOpenShift();
        assertThat(jdbc.queryForObject("SELECT active FROM outlet WHERE id = ?", Boolean.class, second)).isFalse();
        assertThat(openShifts()).isZero();
    }

    @Test
    void racingClockInsAndDeactivationsEndConsistentlyAndNeverDeadlock() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 15; round++) {
                jdbc.update("DELETE FROM timecard_event");
                jdbc.update("UPDATE outlet SET active = TRUE WHERE id = ?", second);
                CountDownLatch go = new CountDownLatch(1);
                Future<?> clockIn = pool.submit(() -> {
                    go.await();
                    return timecards.clock("888888", TimecardEvent.Type.IN, second, "10.0.0.2");
                });
                Future<?> deactivation = pool.submit(() -> {
                    go.await();
                    return outletAdmin.deactivate(second);
                });
                go.countDown();
                boolean clockedIn = succeeded(clockIn);
                boolean deactivated = succeeded(deactivation);
                // Exactly one wins; both failing would mean a lost update or a deadlock victim.
                assertThat(clockedIn ^ deactivated).as("round %s: clock-in %s, deactivation %s", round, clockedIn, deactivated).isTrue();
                assertOutletNeverClosedWithAnOpenShift();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aLegacyOpenShiftAtAnInactiveOutletCanStillBeClosed() throws Exception {
        event(secondManagerId, "IN", "2026-10-12T02:00:00Z", second);
        jdbc.update("UPDATE outlet SET active = FALSE WHERE id = ?", second);

        call(post(close(second, secondManagerId)), admin(), closeBody("2026-10-12T10:00:00Z", "The outlet was closed with it open"))
                .andExpect(status().isOk());
        assertThat(openShifts()).isZero();
    }

    // ---- helpers ----

    private static void assertStillWaiting(Future<?> operation) {
        try {
            operation.get(400, TimeUnit.MILLISECONDS);
            org.junit.jupiter.api.Assertions.fail("It should be waiting for the other transaction");
        } catch (TimeoutException expected) {
            // waiting, as it should
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether the operation completed without an error (a refusal counts as one); bounded, so a deadlock fails the test. */
    private static boolean succeeded(Future<?> operation) throws Exception {
        try {
            operation.get(15, TimeUnit.SECONDS);
            return true;
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOfAny(com.counterline.pos.common.BusinessRuleException.class,
                    com.counterline.pos.common.ConflictException.class);
            return false;
        }
    }

    private long openShifts() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM timecard_event e WHERE e.type = 'IN' AND NOT EXISTS (
                    SELECT 1 FROM timecard_event n WHERE n.staff_id = e.staff_id AND n.id > e.id)
                """, Long.class);
    }

    private void assertOutletNeverClosedWithAnOpenShift() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM timecard_event e JOIN outlet o ON o.id = e.outlet_id
                WHERE e.type = 'IN' AND NOT o.active AND NOT EXISTS (
                    SELECT 1 FROM timecard_event n WHERE n.staff_id = e.staff_id AND n.id > e.id)
                """, Long.class)).as("an inactive outlet with an open shift").isZero();
    }

    private void event(Long staffId, String type, String at, long outletId) {
        jdbc.update("INSERT INTO timecard_event (staff_id, type, occurred_at, outlet_id) VALUES (?, ?, ?, ?)",
                staffId, type, Timestamp.from(Instant.parse(at)), outletId);
    }

    private static String close(long outletId, Long staffId) {
        return "/api/v1/dashboard/outlets/" + outletId + "/timecards/" + staffId + "/close";
    }

    private static String closeBody(String outAt, String reason) {
        return "{\"outAt\":\"%s\",\"reason\":\"%s\"}".formatted(outAt, reason);
    }

    private ResultActions report(String day) throws Exception {
        return report(day, day);
    }

    private ResultActions report(String from, String to) throws Exception {
        return call(get("/api/v1/reports/timecards").param("from", from).param("to", to), cashier(1), null).andExpect(status().isOk());
    }

    private ResultActions call(MockHttpServletRequestBuilder request, RequestPostProcessor auth, String json) throws Exception {
        request.with(auth);
        json = com.counterline.pos.staff.StaffVersions.stamped(jdbc, request, json);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    private RequestPostProcessor cashier(long outlet) {
        return jwt().jwt(token -> token.claim("outlet", outlet).subject(cashierId.toString()).claim("name", "Test Cashier"));
    }

    private RequestPostProcessor manager(long outlet, Long id, String name) {
        return jwt().jwt(token -> token.claim("outlet", outlet).subject(id.toString()).claim("role", "MANAGER").claim("name", name).claim("channel", "dashboard"))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("admin-1").claim("role", "ADMIN").claim("name", "Head Office").claim("channel", "dashboard"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
