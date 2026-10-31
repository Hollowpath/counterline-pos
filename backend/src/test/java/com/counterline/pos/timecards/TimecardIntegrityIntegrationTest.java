package com.counterline.pos.timecards;

import com.counterline.pos.TestcontainersConfiguration;
import com.counterline.pos.auth.PinHasher;
import com.counterline.pos.common.BusinessRuleException;
import com.counterline.pos.outlet.OutletProperties;
import com.counterline.pos.staff.Staff;
import com.counterline.pos.staff.StaffRepository;
import com.counterline.pos.staff.StaffRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A person's clock events stay consistent: a clock-in that waited for a deactivation or a move is not recorded; the
 * current state is the latest event by id, whatever times the events carry; a correction's local time is read in the
 * business time zone; and a staff form older than the person is refused.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class TimecardIntegrityIntegrationTest {

    private static final String STAFF = "/api/v1/dashboard/admin/staff";

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
    OpenShifts openShifts;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    OutletProperties outletProperties;

    private long cashier;
    private long otherOutlet;
    private ZoneId zone;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE login_throttle, staff, customer RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO outlet (code, name) VALUES ('O2', 'Outlet 2') ON CONFLICT (code) DO NOTHING");
        otherOutlet = jdbc.queryForObject("SELECT id FROM outlet WHERE code = 'O2'", Long.class);
        cashier = staffRepository.save(new Staff("Test Cashier", pinHasher.hash("111111"), StaffRole.STAFF, 1L)).getId();
        zone = outletProperties.timeZone();
    }

    // ---- recheck after the lock ----

    @Test
    void aClockInThatWaitedForADeactivationIsNotRecorded() throws Exception {
        Throwable refused = clockInWhileRowIsHeld("UPDATE staff SET active = FALSE WHERE id = " + cashier);

        assertThat(refused).isInstanceOf(BusinessRuleException.class).hasMessage("Invalid PIN");
        assertThat(events()).isZero();
        assertThat(openShifts.has(cashier)).isFalse();
    }

    @Test
    void aClockInThatWaitedForAMoveToAnotherOutletIsNotRecordedAtTheOldOne() throws Exception {
        Throwable refused = clockInWhileRowIsHeld("UPDATE staff SET outlet_id = " + otherOutlet + " WHERE id = " + cashier);

        assertThat(refused).isInstanceOf(BusinessRuleException.class).hasMessage("Invalid PIN");
        assertThat(events()).isZero();
        assertThat(openShifts.at(1L)).isFalse();
    }

    @Test
    void aClockInWhosePinWasResetWhileItWaitedIsNotRecorded() throws Exception {
        // The PIN that was checked is no longer the person's by the time their row is locked.
        Throwable refused = clockInWhileRowIsHeld("UPDATE staff SET pin_hmac = repeat('a', length(pin_hmac)) WHERE id = " + cashier);

        assertThat(refused).isInstanceOf(BusinessRuleException.class).hasMessage("Invalid PIN");
        assertThat(events()).isZero();
        assertThat(openShifts.has(cashier)).isFalse();
    }

    @Test
    void aClockInWithNothingChangedIsRecorded() throws Exception {
        Throwable refused = clockInWhileRowIsHeld("UPDATE staff SET full_name = 'Renamed Cashier' WHERE id = " + cashier);

        assertThat(refused).isNull();
        assertThat(events()).isEqualTo(1);
        assertThat(openShifts.has(cashier)).isTrue();
    }

    // ---- the latest event is the last one recorded ----

    @Test
    void theCurrentStateIsTheLatestEventByIdWhateverTimesTheEventsCarry() throws Exception {
        long outAfterIn = staff("Out Last", "222222");
        long inAfterOut = staff("In Last", "333333");
        // Recorded in this order, with the times the other way round (a clock that stepped back).
        event(outAfterIn, "IN", Instant.now().plusSeconds(120));
        event(outAfterIn, "OUT", Instant.now().minusSeconds(60));
        event(inAfterOut, "OUT", Instant.now().plusSeconds(120));
        event(inAfterOut, "IN", Instant.now().minusSeconds(60));

        assertThat(openShifts.has(outAfterIn)).isFalse();
        assertThat(openShifts.has(inAfterOut)).isTrue();
        assertThat(openShifts.at(1L)).isTrue();
        assertThat(timecards.status("222222", 1L, "10.0.0.1").clockedIn()).isFalse();
        assertThat(timecards.status("333333", 1L, "10.0.0.1").clockedIn()).isTrue();
        // The report lists the same one open, and the correction agrees about who has an open shift.
        mockMvc.perform(get("/api/v1/dashboard/outlets/1/reports/timecards").param("from", today()).param("to", today()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openShifts.length()").value(1))
                .andExpect(jsonPath("$.openShifts[0].staffName").value("In Last"));
        close(outAfterIn, "{\"outAt\":\"" + Instant.now().minusSeconds(30) + "\",\"reason\":\"Test\"}").andExpect(status().isConflict());
    }

    @Test
    void anEventIsDatedByTheDatabaseClock() throws Exception {
        event(cashier, "OUT", Instant.now().minus(1, ChronoUnit.HOURS));
        Instant before = databaseNow();

        timecards.clock("111111", TimecardEvent.Type.IN, 1L, "10.0.0.1");

        Instant after = databaseNow();
        Instant at = jdbc.queryForObject("SELECT occurred_at FROM timecard_event WHERE type = 'IN'", OffsetDateTime.class).toInstant();
        assertThat(at).isBetween(before, after);
    }

    @Test
    void aPreviousEventDatedAFewSecondsAheadIsLoggedAndItsTimeIsNotCopied(CapturedOutput output) throws Exception {
        Instant inAt = Instant.now().plusSeconds(10).truncatedTo(ChronoUnit.MILLIS);
        event(cashier, "IN", inAt);

        timecards.clock("111111", TimecardEvent.Type.OUT, 1L, "10.0.0.1");

        Instant outAt = jdbc.queryForObject("SELECT occurred_at FROM timecard_event WHERE type = 'OUT'", OffsetDateTime.class).toInstant();
        assertThat(outAt).isBefore(inAt);
        assertThat(openShifts.has(cashier)).isFalse();
        assertThat(output.getAll()).contains("which is dated ahead of the database's time");
    }

    @Test
    void aFarFutureLastEventNoLongerLocksAnyoneOutAndNeverLendsItsTime(CapturedOutput output) throws Exception {
        Instant farFuture = Instant.now().plus(2, ChronoUnit.DAYS);
        event(cashier, "IN", farFuture);

        // Normal clocking carries on: out, in, out again, each dated truly and ordered by id.
        Instant start = databaseNow();
        timecards.clock("111111", TimecardEvent.Type.OUT, 1L, "10.0.0.1");
        assertThat(openShifts.has(cashier)).isFalse();
        timecards.clock("111111", TimecardEvent.Type.IN, 1L, "10.0.0.1");
        assertThat(openShifts.has(cashier)).isTrue();
        timecards.clock("111111", TimecardEvent.Type.OUT, 1L, "10.0.0.1");
        assertThat(openShifts.has(cashier)).isFalse();

        Instant end = databaseNow();
        assertThat(jdbc.queryForList("SELECT occurred_at FROM timecard_event WHERE id > (SELECT min(id) FROM timecard_event) ORDER BY id",
                OffsetDateTime.class)).hasSize(3).allSatisfy(at -> assertThat(at.toInstant()).isBetween(start, end));
        // The bad record is warned about on the first event after it, and nowhere else changed.
        assertThat(output.getAll()).contains("which is dated ahead of the database's time");
        assertThat(timecards.status("111111", 1L, "10.0.0.1").clockedIn()).isFalse();
    }

    @Test
    void aShiftWhoseClockInIsDatedAheadCanStillBeClosedFromTheDashboard(CapturedOutput output) throws Exception {
        event(cashier, "IN", Instant.now().plus(1, ChronoUnit.DAYS));

        close(cashier, "{\"outAt\":\"" + Instant.now().minusSeconds(60) + "\",\"reason\":\"Bad clock\"}").andExpect(status().isOk());

        assertThat(openShifts.has(cashier)).isFalse();
        assertThat(output.getAll()).contains("dated ahead of the database's time");
        // A clock-out in the future is still refused.
        event(cashier, "IN", Instant.now().plus(1, ChronoUnit.DAYS));
        close(cashier, "{\"outAt\":\"" + Instant.now().plus(1, ChronoUnit.HOURS) + "\",\"reason\":\"Too far\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("The clock-out cannot be in the future"));
    }

    @Test
    void aFutureDatedClockInIsClosedNoEarlierThanThePreviousEventAndNoLaterThanNow() throws Exception {
        Instant previous = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        event(cashier, "OUT", previous);
        event(cashier, "IN", Instant.now().plus(1, ChronoUnit.DAYS));

        close(cashier, "{\"outAt\":\"" + previous.minusSeconds(60) + "\",\"reason\":\"Too early\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The clock-out cannot be before the staff member's previous event"));
        close(cashier, "{\"outAt\":\"" + Instant.now().plus(1, ChronoUnit.HOURS) + "\",\"reason\":\"Too late\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The clock-out cannot be in the future"));
        assertThat(openShifts.has(cashier)).isTrue();

        // Exactly the previous event is the earliest allowed.
        close(cashier, "{\"outAt\":\"" + previous + "\",\"reason\":\"Bad clock\"}").andExpect(status().isOk());
        assertThat(openShifts.has(cashier)).isFalse();
    }

    @Test
    void theRepairLowerBoundSkipsEarlierEventsThemselvesDatedAheadAndIsNoneIfNoOthersExist() throws Exception {
        Instant sound = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        event(cashier, "OUT", sound);
        event(cashier, "IN", Instant.now().plus(2, ChronoUnit.DAYS));
        event(cashier, "OUT", Instant.now().plus(3, ChronoUnit.DAYS));
        event(cashier, "IN", Instant.now().plus(1, ChronoUnit.DAYS));

        // The two earlier future-dated events are ignored; the sound one before them is the bound.
        close(cashier, "{\"outAt\":\"" + sound.minusSeconds(60) + "\",\"reason\":\"Too early\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The clock-out cannot be before the staff member's previous event"));
        close(cashier, "{\"outAt\":\"" + Instant.now().minusSeconds(60) + "\",\"reason\":\"Bad clock\"}").andExpect(status().isOk());
        assertThat(openShifts.has(cashier)).isFalse();

        // With no sound earlier event at all there is no lower bound, only the upper one.
        jdbc.update("DELETE FROM timecard_event WHERE staff_id = ?", cashier);
        event(cashier, "IN", Instant.now().plus(1, ChronoUnit.DAYS));
        close(cashier, "{\"outAt\":\"" + Instant.now().minus(30, ChronoUnit.DAYS) + "\",\"reason\":\"Long ago\"}").andExpect(status().isOk());
        event(cashier, "IN", Instant.now().plus(1, ChronoUnit.DAYS));
        close(cashier, "{\"outAt\":\"" + Instant.now().plus(1, ChronoUnit.HOURS) + "\",\"reason\":\"Too late\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("The clock-out cannot be in the future"));
    }

    @Test
    void aNonexistentOutletIsNotFoundBeforeAnyShiftIsLookedAt() throws Exception {
        String body = "{\"outAt\":\"" + Instant.now().minusSeconds(3600) + "\",\"reason\":\"Probe\"}";
        String missing = "/api/v1/dashboard/outlets/987654321/timecards/" + cashier + "/close";

        mockMvc.perform(post(missing).with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        // A manager is told the same for an outlet that is not theirs, whether or not it exists.
        mockMvc.perform(post(missing).with(manager1()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/dashboard/outlets/" + otherOutlet + "/timecards/" + cashier + "/close").with(manager1())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        // An existing outlet where the person has no open shift is still the generic 409.
        mockMvc.perform(post("/api/v1/dashboard/outlets/" + otherOutlet + "/timecards/" + cashier + "/close").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    // ---- a correction does not reveal who works where ----

    @Test
    void aManagerCannotTellAnotherOutletsStaffFromNobodyOrFromSomeoneWithoutAShift() throws Exception {
        long elsewhere = jdbc.queryForObject("INSERT INTO staff (full_name, pin_hmac, role, outlet_id, active, version, auth_session_version) "
                + "VALUES ('Other Outlet Cashier', ?, 'STAFF', ?, TRUE, 0, 0) RETURNING id", Long.class, pinHasher.hash("222222"), otherOutlet);
        long idle = staff("Idle Colleague", "333333");
        // The other outlet's person has an open shift there; the colleague at this outlet has none.
        jdbc.update("INSERT INTO timecard_event (staff_id, type, occurred_at, outlet_id) VALUES (?, 'IN', now() - interval '3 hours', ?)",
                elsewhere, otherOutlet);
        String body = "{\"outAt\":\"" + Instant.now().minusSeconds(3600) + "\",\"reason\":\"Probe\"}";

        String generic = "There is no open shift for that staff member at this outlet";
        for (long probed : new long[] {elsewhere, 987_654_321L, idle}) {
            mockMvc.perform(post("/api/v1/dashboard/outlets/1/timecards/" + probed + "/close").with(manager1())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value(generic));
        }
        // The probing changed nothing and took no one's lock for long: the other outlet's shift is still theirs to close.
        assertThat(events()).isEqualTo(1);
        mockMvc.perform(post("/api/v1/dashboard/outlets/" + otherOutlet + "/timecards/" + elsewhere + "/close").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.staffName").value("Other Outlet Cashier"));
        // The manager's own outlet: someone with an open shift there is named once it is closed.
        event(cashier, "IN", Instant.now().minus(3, ChronoUnit.HOURS));
        mockMvc.perform(post("/api/v1/dashboard/outlets/1/timecards/" + cashier + "/close").with(manager1())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.staffName").value("Test Cashier"));
    }

    // ---- a correction's local time is the business time ----

    @Test
    void aCorrectionsLocalTimeIsReadInTheBusinessTimeZoneNotTheBrowsers() throws Exception {
        event(cashier, "IN", Instant.now().minus(5, ChronoUnit.HOURS));
        LocalDateTime wall = LocalDateTime.now(zone).minusHours(1).truncatedTo(ChronoUnit.MINUTES);

        close(cashier, "{\"outAtLocal\":\"" + wall.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "\",\"reason\":\"Forgot\"}")
                .andExpect(status().isOk());

        Instant stored = jdbc.queryForObject("SELECT occurred_at FROM timecard_event WHERE corrected_by IS NOT NULL",
                OffsetDateTime.class).toInstant();
        assertThat(stored).isEqualTo(wall.atZone(zone).toInstant());
        // Not the same wall time read as UTC (what a browser in another zone would have sent as an instant).
        if (!zone.getRules().getOffset(Instant.now()).equals(ZoneOffset.UTC)) {
            assertThat(stored).isNotEqualTo(wall.toInstant(ZoneOffset.UTC));
        }
    }

    @Test
    void aLocalTimeAheadOfTheBusinessClockIsInTheFutureWhateverTheServersZone() throws Exception {
        event(cashier, "IN", Instant.now().minus(5, ChronoUnit.HOURS));
        LocalDateTime ahead = LocalDateTime.now(zone).plusMinutes(30).truncatedTo(ChronoUnit.MINUTES);

        close(cashier, "{\"outAtLocal\":\"" + ahead + "\",\"reason\":\"Too soon\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("The clock-out cannot be in the future"));
        // Before the clock-in, read in the same zone.
        LocalDateTime beforeIn = LocalDateTime.now(zone).minusHours(6).truncatedTo(ChronoUnit.MINUTES);
        close(cashier, "{\"outAtLocal\":\"" + beforeIn + "\",\"reason\":\"Too early\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("The clock-out cannot be before the clock-in"));
        assertThat(openShifts.has(cashier)).isTrue();
    }

    @Test
    void aMalformedCorrectionIsABadRequestBeforeAnyShiftIsLookedAt() throws Exception {
        // Nobody has an open shift here, which is a 409 once the shift is looked at: the body is judged first.
        LocalDateTime wall = LocalDateTime.now(zone).minusHours(1).truncatedTo(ChronoUnit.MINUTES);
        close(cashier, "{\"reason\":\"No time\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Give the time of the clock-out"));
        close(cashier, "{\"outAt\":\"" + Instant.now().minusSeconds(60) + "\",\"outAtLocal\":\"" + wall + "\",\"reason\":\"Two\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Give the time of the clock-out"));
        close(cashier, "{\"outAtLocal\":\"" + wall + "\",\"reason\":\"   \"}").andExpect(status().isBadRequest());
        // A well-formed one for someone with no open shift is the conflict.
        close(cashier, "{\"outAtLocal\":\"" + wall + "\",\"reason\":\"Well formed\"}").andExpect(status().isConflict());
    }

    @Test
    void aCorrectionNeedsExactlyOneTimeAndTheReportNamesTheZone() throws Exception {
        event(cashier, "IN", Instant.now().minus(5, ChronoUnit.HOURS));
        LocalDateTime wall = LocalDateTime.now(zone).minusHours(1).truncatedTo(ChronoUnit.MINUTES);

        close(cashier, "{\"reason\":\"No time\"}").andExpect(status().isBadRequest());
        close(cashier, "{\"outAt\":\"" + Instant.now().minusSeconds(60) + "\",\"outAtLocal\":\"" + wall + "\",\"reason\":\"Two times\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Give the time of the clock-out"));
        mockMvc.perform(get("/api/v1/dashboard/outlets/1/reports/timecards").param("from", today()).param("to", today()).with(admin()))
                .andExpect(jsonPath("$.timeZone").value(zone.getId()));
    }

    // ---- a stale staff form ----

    @Test
    void aStaffFormOlderThanThePersonIsRefusedWithTheUsualConflictAndWritesNothing() throws Exception {
        long version = jdbc.queryForObject("SELECT version FROM staff WHERE id = ?", Long.class, cashier);

        // Admin A saves from the version both forms were loaded at.
        edit(cashier, "Renamed By A", version).andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Renamed By A"))
                .andExpect(jsonPath("$.version").value(version + 1));
        // Admin B still holds the old one: the later save is refused, not written over A's.
        edit(cashier, "Renamed By B", version).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Changed elsewhere. Reload."));
        assertThat(jdbc.queryForObject("SELECT full_name FROM staff WHERE id = ?", String.class, cashier)).isEqualTo("Renamed By A");

        // Reloaded, B's edit goes through; a form that names no version is a bad request.
        edit(cashier, "Renamed By B", version + 1).andExpect(status().isOk());
        mockMvc.perform(put(STAFF + "/" + cashier).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"No version\",\"role\":\"STAFF\",\"outletId\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("version is required when editing"));
        assertThat(jdbc.queryForObject("SELECT full_name FROM staff WHERE id = ?", String.class, cashier)).isEqualTo("Renamed By B");
    }

    @Test
    void aPinResetOrDeactivationMakesAnOlderFormStaleToo() throws Exception {
        long version = jdbc.queryForObject("SELECT version FROM staff WHERE id = ?", Long.class, cashier);

        mockMvc.perform(post(STAFF + "/" + cashier + "/pin").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\":\"654321\"}")).andExpect(status().isOk());

        edit(cashier, "Late Edit", version).andExpect(status().isConflict());
    }

    // ---- helpers ----

    /**
     * Starts a clock-in while another transaction holds the person's row with {@code change} uncommitted, waits until the
     * clock-in is really blocked behind it, then commits the change. Returns what the clock-in threw, or null.
     */
    private Throwable clockInWhileRowIsHeld(String change) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch commit = new CountDownLatch(1);
            Future<?> holder = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.update(change);
                held.countDown();
                try {
                    commit.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(held.await(20, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> clocking = executor.submit(() -> {
                try {
                    timecards.clock("111111", TimecardEvent.Type.IN, 1L, "10.0.0.1");
                    return null;
                } catch (Throwable e) {
                    return e;
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE datname = current_database() AND cardinality(pg_blocking_pids(pid)) > 0", Integer.class) == 0) {
                assertThat(clocking.isDone()).as("the clock-in never waited for the person's row").isFalse();
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.sleep(5);
            }
            commit.countDown();
            holder.get(20, TimeUnit.SECONDS);
            return clocking.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private long staff(String name, String pin) {
        return staffRepository.save(new Staff(name, pinHasher.hash(pin), StaffRole.STAFF, 1L)).getId();
    }

    private void event(long staffId, String type, Instant at) {
        jdbc.update("INSERT INTO timecard_event (staff_id, type, occurred_at, outlet_id) VALUES (?, ?, ?, 1)",
                staffId, type, Timestamp.from(at));
    }

    private int events() {
        return jdbc.queryForObject("SELECT count(*) FROM timecard_event", Integer.class);
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
    }

    private String today() {
        return java.time.LocalDate.now(zone).toString();
    }

    private ResultActions close(long staffId, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/dashboard/outlets/1/timecards/" + staffId + "/close").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions edit(long staffId, String name, long version) throws Exception {
        return mockMvc.perform(put(STAFF + "/" + staffId).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"" + name + "\",\"role\":\"STAFF\",\"outletId\":1,\"version\":" + version + "}"));
    }

    /** The manager of outlet 1. */
    private static RequestPostProcessor manager1() {
        return jwt().jwt(token -> token.subject("1").claim("role", "MANAGER").claim("outlet", 1L).claim("name", "Manager")
                        .claim("channel", "dashboard"))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("admin-1").claim("role", "ADMIN").claim("name", "Head Office").claim("channel", "dashboard"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
