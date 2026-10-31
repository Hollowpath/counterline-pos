package com.counterline.pos.auth;

import com.counterline.pos.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Against PostgreSQL, where the counts are kept; the clock is the test's. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class LoginAttemptLimiterTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-12T09:00:00Z"));
    private LoginAttemptLimiter limiter;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM login_throttle");
        limiter = new LoginAttemptLimiter(jdbc,
                new SecurityProperties("pepper", "x".repeat(32), Duration.ofHours(12), Duration.ofMinutes(5),
                        new SecurityProperties.LoginThrottle(10, Duration.ofMinutes(5))),
                clock);
    }

    @Test
    void theTenthWrongPinInARowLocksThePadAtOnce() {
        for (int attempt = 1; attempt <= 9; attempt++) {
            assertThat(limiter.recordFailure("till:1")).isEqualTo(10 - attempt);
        }
        assertThatCode(() -> limiter.checkAllowed("till:1")).doesNotThrowAnyException();

        // The 10th is refused as a lock-out itself, not only the attempt after it.
        assertThatThrownBy(() -> limiter.recordFailure("till:1"))
                .isInstanceOfSatisfying(TooManyLoginAttemptsException.class,
                        ex -> assertThat(ex.retryAfterSeconds()).isEqualTo(300));
        assertThatThrownBy(() -> limiter.checkAllowed("till:1")).isInstanceOf(TooManyLoginAttemptsException.class);
    }

    @Test
    void eachOutletsPadIsCountedOnItsOwn() {
        failTimes("till:1", 9);
        failTimesAndLock("till:1");

        assertThatCode(() -> limiter.checkAllowed("till:2")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.checkAllowed("clock:1")).doesNotThrowAnyException();
        assertThat(limiter.recordFailure("till:2")).isEqualTo(9);
    }

    @Test
    void theLockCountsDownAndEnds() {
        failTimes("clock:1", 9);
        failTimesAndLock("clock:1");
        clock.advance(Duration.ofSeconds(100));

        assertThatThrownBy(() -> limiter.checkAllowed("clock:1"))
                .isInstanceOfSatisfying(TooManyLoginAttemptsException.class,
                        ex -> assertThat(ex.retryAfterSeconds()).isEqualTo(200));

        clock.advance(Duration.ofSeconds(200));
        assertThatCode(() -> limiter.checkAllowed("clock:1")).doesNotThrowAnyException();
        // A fresh start after the lock.
        assertThat(limiter.recordFailure("clock:1")).isEqualTo(9);
    }

    @Test
    void wrongPinsAWindowApartAreNotInARow() {
        failTimes("till:1", 9);
        clock.advance(Duration.ofMinutes(5));

        assertThat(limiter.recordFailure("till:1")).isEqualTo(9);
    }

    @Test
    void anUnlockStartsTheCountAgain() {
        failTimes("till:1", 9);
        failTimesAndLock("till:1");
        assertThat(limiter.locked("till:")).extracting(LoginAttemptLimiter.Lock::key).containsExactly("till:1");
        limiter.unlock("till:1");
        assertThatCode(() -> limiter.checkAllowed("till:1")).doesNotThrowAnyException();
        assertThat(limiter.locked("till:")).isEmpty();
    }

    @Test
    void theLastThreeTriesAreWarnedAbout() {
        assertThat(limiter.withTriesLeft("Invalid PIN", 4)).isEqualTo("Invalid PIN");
        assertThat(limiter.withTriesLeft("Invalid PIN", 3))
                .isEqualTo("Invalid PIN · 3 tries left");
        assertThat(limiter.withTriesLeft(SpecialAccessDeniedException.MESSAGE, 1)).isEqualTo("Manager PIN · 1 try left");
    }

    // ---- Admission: an attempt is counted before it is checked ----

    @Test
    void onlyTheLimitsWorthOfAttemptsAreAdmittedAtOnceAndTheRestAreRefusedWithoutLockingTheKey() {
        java.util.List<LoginAttemptLimiter.Attempt> admitted = new java.util.ArrayList<>();
        for (int attempt = 1; attempt <= 10; attempt++) {
            admitted.add(limiter.begin(new LoginAttemptLimiter.Limit("till:1", 10)));
        }

        // The 11th, while ten are under way, is refused; the key is not locked by that.
        assertThatThrownBy(() -> limiter.begin(new LoginAttemptLimiter.Limit("till:1", 10)))
                .isInstanceOf(TooManyLoginAttemptsException.class);
        assertThat(limiter.locked("till:")).isEmpty();
        assertThat(underWay("till:1")).isEqualTo(10);
        assertThat(failures("till:1")).isZero();

        // A right guess gives its place back, so another can be admitted; the wrong ones lock the key at the tenth.
        admitted.get(0).succeeded();
        assertThat(underWay("till:1")).isEqualTo(9);
        LoginAttemptLimiter.Attempt replacement = limiter.begin(new LoginAttemptLimiter.Limit("till:1", 10));
        for (int i = 1; i < 10; i++) {
            admitted.get(i).failed();
        }
        assertThatThrownBy(replacement::failed).isInstanceOf(TooManyLoginAttemptsException.class);
        assertThat(limiter.locked("till:")).extracting(LoginAttemptLimiter.Lock::key).containsExactly("till:1");
    }

    @Test
    void aSuccessGivesBackItsPlaceAndNeverClearsAnotherClientsFailures() {
        limiter.begin(new LoginAttemptLimiter.Limit("dashboard:10.0.0.1", 50)).failed();
        limiter.begin(new LoginAttemptLimiter.Limit("dashboard:10.0.0.1", 50)).failed();
        LoginAttemptLimiter.Attempt other = limiter.begin(new LoginAttemptLimiter.Limit("dashboard:10.0.0.2", 50));
        other.succeeded();

        assertThat(failures("dashboard:10.0.0.1")).isEqualTo(2);
        assertThat(failures("dashboard:10.0.0.2")).isZero();
        assertThat(underWay("dashboard:10.0.0.2")).isZero();
        // Closing an attempt that was never settled gives its place back too.
        try (LoginAttemptLimiter.Attempt unsettled = limiter.begin(new LoginAttemptLimiter.Limit("till:7", 10))) {
            assertThat(underWay("till:7")).isEqualTo(1);
        }
        assertThat(underWay("till:7")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_throttle WHERE throttle_key = 'till:7'", Integer.class)).isZero();
    }

    @Test
    void anAttemptRefusedOnALaterKeyGivesBackTheEarlierOnes() {
        failTimesAndLockAt("dashboard-account:x", 3);

        assertThatThrownBy(() -> limiter.begin(new LoginAttemptLimiter.Limit("dashboard:addr", 50),
                new LoginAttemptLimiter.Limit("dashboard-account:x", 3)))
                .isInstanceOf(TooManyLoginAttemptsException.class);

        assertThat(failures("dashboard:addr")).isZero();
    }

    @Test
    void staleRowsStartAgainAndAreSweptAwayAFewAtATime() {
        failTimes("till:1", 9);
        for (int i = 0; i < 150; i++) {
            jdbc.update("INSERT INTO login_throttle (throttle_key, failures, last_failed_at) VALUES (?, 1, ?)",
                    "old:" + i, java.sql.Timestamp.from(clock.instant().minus(Duration.ofMinutes(10))));
        }

        limiter.begin(new LoginAttemptLimiter.Limit("till:2", 10)).failed();

        // One failure sweeps at most a hundred stale rows; the live ones are untouched.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_throttle WHERE throttle_key LIKE 'old:%'", Integer.class)).isEqualTo(50);
        assertThat(failures("till:1")).isEqualTo(9);

        // After a window the old count is gone: the next attempt counts as the first.
        clock.advance(Duration.ofMinutes(5));
        limiter.begin(new LoginAttemptLimiter.Limit("till:1", 10)).failed();
        assertThat(failures("till:1")).isEqualTo(1);
    }

    @Test
    void aQuotaAdmitsItsLimitInAFixedWindowRefundsAndStartsAgain() {
        for (int i = 0; i < 5; i++) {
            limiter.consume("display-signup:1", 5);
        }
        assertThatThrownBy(() -> limiter.consume("display-signup:1", 5))
                .isInstanceOfSatisfying(TooManyLoginAttemptsException.class, ex -> assertThat(ex.retryAfterSeconds()).isEqualTo(300));
        assertThat(limiter.locked("display-signup:")).isEmpty();

        limiter.refund("display-signup:1");
        limiter.consume("display-signup:1", 5);
        assertThatThrownBy(() -> limiter.consume("display-signup:1", 5)).isInstanceOf(TooManyLoginAttemptsException.class);

        clock.advance(Duration.ofMinutes(5));
        limiter.consume("display-signup:1", 5);
        assertThat(failures("display-signup:1")).isEqualTo(1);
        // Another display has a quota of its own.
        limiter.consume("display-signup:2", 5);
    }

    @Test
    void theThrottleRefusesToRunInsideATransaction() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(
                status -> limiter.begin(new LoginAttemptLimiter.Limit("till:1", 10))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("outside a transaction");
    }

    // ---- Settling is exact: one statement per place, whatever else is going on ----


    @Test
    void aReleaseCountsExactlyOnceWhenAnotherAttemptIsAdmittedWhileItWaits() throws Exception {
        LoginAttemptLimiter.Attempt attempt = limiter.begin(new LoginAttemptLimiter.Limit("till:1", 10));

        whileRowIsHeld("UPDATE login_throttle SET pending = pending + 1 WHERE throttle_key = 'till:1'",
                attempt::succeeded);

        // The other attempt's place is still there: its admission was not undone, nor was this one given back twice.
        assertThat(underWay("till:1")).isEqualTo(1);
        attempt.succeeded();
        assertThat(underWay("till:1")).isEqualTo(1);
    }

    @Test
    void aReleaseKeepsTheFailuresCountedWhileItWaited() throws Exception {
        LoginAttemptLimiter.Attempt attempt = limiter.begin(new LoginAttemptLimiter.Limit("till:1", 10));

        whileRowIsHeld("UPDATE login_throttle SET failures = 3 WHERE throttle_key = 'till:1'", attempt::succeeded);

        assertThat(underWay("till:1")).isZero();
        assertThat(failures("till:1")).isEqualTo(3);
    }

    @Test
    void aReleaseOnARowDeletedWhileItWaitedDoesNothingAndBringsNothingBack() throws Exception {
        LoginAttemptLimiter.Attempt attempt = limiter.begin(new LoginAttemptLimiter.Limit("till:1", 10));

        whileRowIsHeld("DELETE FROM login_throttle WHERE throttle_key = 'till:1'", attempt::succeeded);

        assertThat(rows("till:1")).isZero();
    }

    @Test
    void aRefundCountsExactlyOnceWhenAnotherUseIsCountedWhileItWaits() throws Exception {
        limiter.consume("display-signup:1", 5);

        whileRowIsHeld("UPDATE login_throttle SET failures = failures + 1 WHERE throttle_key = 'display-signup:1'",
                () -> limiter.refund("display-signup:1"));

        assertThat(failures("display-signup:1")).isEqualTo(1);
        limiter.refund("display-signup:1");
        assertThat(rows("display-signup:1")).isZero();
        // Nothing left to give back: no row appears and nothing goes below zero.
        limiter.refund("display-signup:1");
        assertThat(rows("display-signup:1")).isZero();
    }

    @Test
    void anEmptyRowIsAllowedAndNegativeCountsAreNot() {
        jdbc.update("INSERT INTO login_throttle (throttle_key, failures, pending, last_failed_at) VALUES ('till:1', 0, 0, ?)",
                java.sql.Timestamp.from(clock.instant()));
        assertThatThrownBy(() -> jdbc.update("UPDATE login_throttle SET pending = -1 WHERE throttle_key = 'till:1'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE login_throttle SET failures = -1 WHERE throttle_key = 'till:1'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        // An empty row is admitted on like a missing one, and tidied away when its attempt is settled.
        limiter.begin(new LoginAttemptLimiter.Limit("till:1", 10)).succeeded();
        assertThat(rows("till:1")).isZero();
    }

    @Test
    void anAttemptSettledTwiceOrClosedAfterwardsGivesBackItsPlaceOnlyOnce() {
        LoginAttemptLimiter.Attempt other = limiter.begin(new LoginAttemptLimiter.Limit("till:1", 10));
        LoginAttemptLimiter.Attempt attempt = limiter.begin(new LoginAttemptLimiter.Limit("till:1", 10));

        attempt.succeeded();
        attempt.succeeded();
        attempt.close();
        assertThat(underWay("till:1")).isEqualTo(1);

        LoginAttemptLimiter.Attempt wrong = limiter.begin(new LoginAttemptLimiter.Limit("till:1", 10));
        wrong.failed();
        wrong.close();
        wrong.succeeded();
        assertThat(underWay("till:1")).isEqualTo(1);
        assertThat(failures("till:1")).isEqualTo(1);
        assertThatThrownBy(wrong::failed).isInstanceOf(IllegalStateException.class);
        other.succeeded();
    }

    // ---- Settling and compensating survive a failure on one key ----

    @Test
    void settlingTriesEveryKeyOnceAndClosingNeverRetriesTheOneThatFailed() {
        LoginAttemptLimiter.Attempt bystander = limiter.begin(new LoginAttemptLimiter.Limit("poison:a", 10));
        LoginAttemptLimiter.Attempt attempt = limiter.begin(new LoginAttemptLimiter.Limit("poison:a", 10),
                new LoginAttemptLimiter.Limit("poison:b", 10), new LoginAttemptLimiter.Limit("poison:c", 10));
        faultOnPendingDrop("poison:b");

        assertThatThrownBy(attempt::succeeded).isInstanceOf(org.springframework.dao.DataAccessException.class);

        // The keys either side of the failing one were given back; the failing one still holds its place.
        assertThat(underWay("poison:a")).isEqualTo(1);
        assertThat(rows("poison:c")).isZero();
        assertThat(underWay("poison:b")).isEqualTo(1);

        // Closing it, even with the fault gone, does not try that key again: it stays counted (fail-closed), and the
        // bystander's place on the first key is not touched.
        removeFaults();
        attempt.close();
        attempt.succeeded();
        assertThat(underWay("poison:b")).isEqualTo(1);
        assertThat(underWay("poison:a")).isEqualTo(1);
        bystander.succeeded();
        assertThat(rows("poison:a")).isZero();
    }

    @Test
    void anIndeterminatePlaceIsNeverTakenBackFromALaterAttemptAndExpiresAfterAQuietWindow() {
        LoginAttemptLimiter.Attempt attempt = limiter.begin(new LoginAttemptLimiter.Limit("poison:b", 10));
        faultOnPendingDrop("poison:b");
        assertThatThrownBy(attempt::succeeded).isInstanceOf(org.springframework.dao.DataAccessException.class);
        removeFaults();

        LoginAttemptLimiter.Attempt later = limiter.begin(new LoginAttemptLimiter.Limit("poison:b", 10));
        attempt.close();
        attempt.succeeded();
        // The earlier attempt's place (unknown) and the later one's are both still counted: nothing was taken back.
        assertThat(underWay("poison:b")).isEqualTo(2);
        later.succeeded();
        assertThat(underWay("poison:b")).isEqualTo(1);

        // Nothing admitted for a window: the unknown place is forgotten.
        clock.advance(Duration.ofMinutes(5));
        limiter.begin(new LoginAttemptLimiter.Limit("poison:b", 10)).succeeded();
        assertThat(rows("poison:b")).isZero();
    }

    @Test
    void aRecordFailureThatFailsLeavesItsAdmissionCountedAndTheEarlierFailuresAlone() {
        limiter.recordFailure("poison:r");
        limiter.recordFailure("poison:r");
        faultOnPendingDrop("poison:r");

        assertThatThrownBy(() -> limiter.recordFailure("poison:r")).isInstanceOf(org.springframework.dao.DataAccessException.class);

        // Nothing was undone on a guess: the two earlier failures stand, and the admitted place stays (fail-closed).
        assertThat(failures("poison:r")).isEqualTo(2);
        assertThat(underWay("poison:r")).isEqualTo(1);
        removeFaults();
        assertThat(limiter.recordFailure("poison:r")).isEqualTo(7);
        assertThat(failures("poison:r")).isEqualTo(3);
        assertThat(underWay("poison:r")).isEqualTo(1);
        // The leaked place goes with the quiet window.
        clock.advance(Duration.ofMinutes(5));
        limiter.begin(new LoginAttemptLimiter.Limit("poison:r", 10)).succeeded();
        assertThat(rows("poison:r")).isZero();
    }

    @Test
    void anUnlockForgetsTheFailuresAndTheLockButKeepsAttemptsUnderWayAcrossRowReuse() {
        LoginAttemptLimiter.Attempt inFlight = limiter.begin(new LoginAttemptLimiter.Limit("till:1", 10));
        jdbc.update("UPDATE login_throttle SET failures = 10, locked_until = ? WHERE throttle_key = 'till:1'",
                java.sql.Timestamp.from(clock.instant().plus(Duration.ofMinutes(5))));
        assertThatThrownBy(() -> limiter.checkAllowed("till:1")).isInstanceOf(TooManyLoginAttemptsException.class);

        limiter.unlock("till:1");

        assertThatCode(() -> limiter.checkAllowed("till:1")).doesNotThrowAnyException();
        assertThat(limiter.locked("till:")).isEmpty();
        assertThat(failures("till:1")).isZero();
        assertThat(underWay("till:1")).isEqualTo(1);
        // The row is reused by a new attempt; the one in flight settling does not take its place.
        LoginAttemptLimiter.Attempt next = limiter.begin(new LoginAttemptLimiter.Limit("till:1", 10));
        assertThat(underWay("till:1")).isEqualTo(2);
        inFlight.succeeded();
        assertThat(underWay("till:1")).isEqualTo(1);
        next.succeeded();
        assertThat(rows("till:1")).isZero();
    }

    @Test
    void anUnlockOfARowWithNothingUnderWayTidiesItAwayAndAnEmptyRowIsNotTidiedUnderAnAttempt() throws Exception {
        limiter.begin(new LoginAttemptLimiter.Limit("till:1", 10)).failed();
        limiter.unlock("till:1");
        assertThat(rows("till:1")).isZero();

        // An attempt admitted while an unlock tidies (the unlock's update waits, then its delete sees the attempt) stays.
        limiter.begin(new LoginAttemptLimiter.Limit("till:2", 10)).failed();
        whileRowIsHeld("UPDATE login_throttle SET pending = pending + 1 WHERE throttle_key = 'till:2'", () -> limiter.unlock("till:2"));
        assertThat(rows("till:2")).isEqualTo(1);
        assertThat(underWay("till:2")).isEqualTo(1);
        assertThat(failures("till:2")).isZero();
    }

    @Test
    void aParallelFirstAttemptOnANewKeyIsNotRefusedAsFullJustBecauseItLostTheRaceToCreateTheRow() throws Exception {
        // Another attempt is making the key's row (uncommitted) when ours finds none and tries to make it too.
        whileRowIsHeld("INSERT INTO login_throttle (throttle_key, failures, pending, last_failed_at, pending_at) "
                        + "VALUES ('till:1', 0, 1, '2026-10-12 09:00:00+00', '2026-10-12 09:00:00+00')",
                () -> limiter.begin(new LoginAttemptLimiter.Limit("till:1", 10)));

        assertThat(underWay("till:1")).isEqualTo(2);
    }

    @Test
    void aParallelFirstUseOfANewQuotaIsNotRefusedAsUsedUpJustBecauseItLostTheRaceToCreateTheRow() throws Exception {
        whileRowIsHeld("INSERT INTO login_throttle (throttle_key, failures, last_failed_at) VALUES "
                        + "('display-signup:1', 1, '2026-10-12 09:00:00+00')",
                () -> limiter.consume("display-signup:1", 5));

        assertThat(failures("display-signup:1")).isEqualTo(2);
    }

    @Test
    void aQuotaRowThatVanishesBetweenTheCountAndTheLookupIsCountedAgainNotAnError() {
        for (int i = 0; i < 5; i++) {
            limiter.consume("display-signup:1", 5);
        }
        JdbcTemplate racing = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public <T> java.util.List<T> queryForList(String sql, Class<T> elementType, Object... args) {
                // A sweep (or the tidy-up after a refund) gets the row just before the lookup of the window's start.
                if (sql.contains("SELECT last_failed_at FROM login_throttle")) {
                    update("DELETE FROM login_throttle WHERE throttle_key = 'display-signup:1'");
                }
                return super.queryForList(sql, elementType, args);
            }
        };
        LoginAttemptLimiter limiterOnRacingDatabase = new LoginAttemptLimiter(racing,
                new SecurityProperties("pepper", "x".repeat(32), Duration.ofHours(12), Duration.ofMinutes(5),
                        new SecurityProperties.LoginThrottle(10, Duration.ofMinutes(5))),
                clock);

        assertThatCode(() -> limiterOnRacingDatabase.consume("display-signup:1", 5)).doesNotThrowAnyException();

        assertThat(failures("display-signup:1")).isEqualTo(1);
    }

    @Test
    void theSweepRemovesStaleEmptyRowsAndKeepsFreshOnes() {
        jdbc.update("INSERT INTO login_throttle (throttle_key, failures, pending, last_failed_at) VALUES ('empty:old', 0, 0, ?)",
                java.sql.Timestamp.from(clock.instant().minus(Duration.ofMinutes(6))));
        jdbc.update("INSERT INTO login_throttle (throttle_key, failures, pending, last_failed_at) VALUES ('empty:fresh', 0, 0, ?)",
                java.sql.Timestamp.from(clock.instant().minus(Duration.ofMinutes(1))));

        limiter.begin(new LoginAttemptLimiter.Limit("till:1", 10)).failed();

        assertThat(rows("empty:old")).isZero();
        assertThat(rows("empty:fresh")).isEqualTo(1);
    }

    @Test
    void aWrongGuessIsCountedOnEveryKeyEvenIfOneKeyFails() {
        LoginAttemptLimiter.Attempt attempt = limiter.begin(new LoginAttemptLimiter.Limit("poison:a", 10),
                new LoginAttemptLimiter.Limit("poison:b", 10), new LoginAttemptLimiter.Limit("poison:c", 10));
        faultOnPendingDrop("poison:b");

        assertThatThrownBy(attempt::failed).isInstanceOf(org.springframework.dao.DataAccessException.class);

        assertThat(failures("poison:a")).isEqualTo(1);
        assertThat(failures("poison:c")).isEqualTo(1);
        assertThat(failures("poison:b")).isZero();
        assertThat(underWay("poison:b")).isEqualTo(1);

        // Closing does not try the failing key again (its place stays counted); the counted keys are left as they are.
        removeFaults();
        attempt.close();
        attempt.close();
        assertThat(underWay("poison:b")).isEqualTo(1);
        assertThat(failures("poison:b")).isZero();
        assertThat(failures("poison:a")).isEqualTo(1);
        assertThat(failures("poison:c")).isEqualTo(1);
        assertThat(underWay("poison:a")).isZero();
    }

    @Test
    void aRefusedAdmissionIsWhatIsThrownEvenWhenGivingBackAnEarlierKeyFails() {
        failTimesAndLockAt("poison:z", 2);
        faultOnPendingDrop("poison:b");

        assertThatThrownBy(() -> limiter.begin(new LoginAttemptLimiter.Limit("poison:a", 10),
                new LoginAttemptLimiter.Limit("poison:b", 10), new LoginAttemptLimiter.Limit("poison:z", 2)))
                .isInstanceOfSatisfying(TooManyLoginAttemptsException.class, ex -> assertThat(ex.getSuppressed())
                        .singleElement().isInstanceOf(org.springframework.dao.DataAccessException.class));

        // The key given back after the failing one (earlier in the list) was still given back.
        assertThat(rows("poison:a")).isZero();
        assertThat(underWay("poison:b")).isEqualTo(1);
    }

    @Test
    void anErrorWhileAdmittingIsWhatIsThrownAndEarlierKeysAreGivenBack() {
        jdbc.execute("CREATE OR REPLACE FUNCTION throttle_test_fault() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'injected fault'; END $$");
        jdbc.execute("CREATE TRIGGER throttle_test_insert_fault BEFORE INSERT ON login_throttle FOR EACH ROW "
                + "WHEN (NEW.throttle_key = 'poison:d') EXECUTE FUNCTION throttle_test_fault()");

        assertThatThrownBy(() -> limiter.begin(new LoginAttemptLimiter.Limit("poison:a", 10),
                new LoginAttemptLimiter.Limit("poison:d", 10)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .satisfies(ex -> assertThat(ex.getSuppressed()).isEmpty());

        assertThat(rows("poison:a")).isZero();
    }

    @Test
    void aFailedSweepIsLoggedAndLeavesTheWrongGuessCounted(CapturedOutput output) {
        jdbc.update("INSERT INTO login_throttle (throttle_key, failures, last_failed_at) VALUES ('poison:sweep', 1, ?)",
                java.sql.Timestamp.from(clock.instant().minus(Duration.ofMinutes(10))));
        jdbc.execute("CREATE OR REPLACE FUNCTION throttle_test_fault() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'injected fault'; END $$");
        jdbc.execute("CREATE TRIGGER throttle_test_sweep_fault BEFORE DELETE ON login_throttle FOR EACH ROW "
                + "WHEN (OLD.throttle_key = 'poison:sweep') EXECUTE FUNCTION throttle_test_fault()");

        assertThat(limiter.begin(new LoginAttemptLimiter.Limit("till:1", 10)).failed()).isEqualTo(9);
        assertThat(limiter.recordFailure("till:2")).isEqualTo(9);

        assertThat(failures("till:1")).isEqualTo(1);
        assertThat(underWay("till:1")).isZero();
        assertThat(failures("till:2")).isEqualTo(1);
        assertThat(rows("poison:sweep")).isEqualTo(1);
        assertThat(output.getAll()).contains("Login throttle sweep failed");
        limiter.begin(new LoginAttemptLimiter.Limit("till:3", 10)).succeeded();
        assertThat(rows("till:3")).isZero();
    }

    // ---- An attempt still under way is not forgotten when the failures are ----

    @Test
    void anAttemptStillRunningWhenTheRowCrossesTheCutoffKeepsItsPlace() {
        LoginAttemptLimiter.Attempt first = limiter.begin(new LoginAttemptLimiter.Limit("till:1", 3));
        clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        LoginAttemptLimiter.Attempt running = limiter.begin(new LoginAttemptLimiter.Limit("till:1", 3));
        clock.advance(Duration.ofSeconds(2));

        // The row's last stamp is a window old now, but the second attempt was admitted two seconds ago.
        LoginAttemptLimiter.Attempt later = limiter.begin(new LoginAttemptLimiter.Limit("till:1", 3));
        assertThat(underWay("till:1")).isEqualTo(3);
        // ...and it still counts against the limit.
        assertThatThrownBy(() -> limiter.begin(new LoginAttemptLimiter.Limit("till:1", 3)))
                .isInstanceOf(TooManyLoginAttemptsException.class);

        running.succeeded();
        first.succeeded();
        later.succeeded();
        assertThat(rows("till:1")).isZero();
    }

    @Test
    void crossingTheCutoffStillStartsTheWindowAgainAndForgetsOldFailures() {
        failTimes("till:1", 9);
        clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        LoginAttemptLimiter.Attempt running = limiter.begin(new LoginAttemptLimiter.Limit("till:1", 10));
        clock.advance(Duration.ofSeconds(1));

        // A window since the last wrong attempt: the nine are forgotten (not a sliding window), the running attempt is not.
        LoginAttemptLimiter.Attempt later = limiter.begin(new LoginAttemptLimiter.Limit("till:1", 10));
        assertThat(failures("till:1")).isZero();
        assertThat(underWay("till:1")).isEqualTo(2);
        assertThat(limiter.lockedForSeconds("till:1")).isZero();
        // The new window starts at this admission, as it always did.
        assertThat(jdbc.queryForObject("SELECT last_failed_at FROM login_throttle WHERE throttle_key = 'till:1'",
                java.sql.Timestamp.class).toInstant()).isEqualTo(clock.instant());
        running.succeeded();
        later.succeeded();
        assertThat(rows("till:1")).isZero();
    }

    @Test
    void aPlaceNobodySettledIsForgottenOnceAWindowPassesWithNothingAdmitted() {
        limiter.begin(new LoginAttemptLimiter.Limit("till:1", 10)); // never settled: the process died
        clock.advance(Duration.ofMinutes(5));

        limiter.begin(new LoginAttemptLimiter.Limit("till:1", 10));

        assertThat(underWay("till:1")).isEqualTo(1);
    }

    @Test
    void theSweepLeavesAStaleRowWithAnAttemptStillUnderWay() {
        LoginAttemptLimiter.Attempt first = limiter.begin(new LoginAttemptLimiter.Limit("till:1", 10));
        clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        LoginAttemptLimiter.Attempt running = limiter.begin(new LoginAttemptLimiter.Limit("till:1", 10));
        clock.advance(Duration.ofSeconds(2));

        limiter.begin(new LoginAttemptLimiter.Limit("till:2", 10)).failed();

        assertThat(underWay("till:1")).isEqualTo(2);
        running.succeeded();
        first.succeeded();
    }

    // ---- helpers for the above ----

    private int rows(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM login_throttle WHERE throttle_key = ?", Integer.class, key);
    }

    /** A statement that takes a place or a use back on this key fails (a stand-in for the database failing on one key). */
    private void faultOnPendingDrop(String key) {
        jdbc.execute("CREATE OR REPLACE FUNCTION throttle_test_fault() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'injected fault'; END $$");
        jdbc.execute("CREATE TRIGGER throttle_test_release_fault BEFORE UPDATE ON login_throttle FOR EACH ROW "
                + "WHEN (NEW.pending < OLD.pending AND NEW.throttle_key = '" + key + "') EXECUTE FUNCTION throttle_test_fault()");
    }

    @AfterEach
    void removeFaults() {
        jdbc.execute("DROP TRIGGER IF EXISTS throttle_test_release_fault ON login_throttle");
        jdbc.execute("DROP TRIGGER IF EXISTS throttle_test_insert_fault ON login_throttle");
        jdbc.execute("DROP TRIGGER IF EXISTS throttle_test_sweep_fault ON login_throttle");
        jdbc.execute("DROP FUNCTION IF EXISTS throttle_test_fault()");
    }

    /**
     * Holds a row change uncommitted in one transaction, starts {@code action} on another thread, waits until its
     * some session is really blocked behind that transaction (PostgreSQL's own {@code pg_blocking_pids}, whatever the
     * statement's text), and only then commits: the action's statement runs on the row as the other transaction left it.
     * It waits on that observed state, not on a delay.
     */
    private void whileRowIsHeld(String holdSql, Runnable action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch commit = new CountDownLatch(1);
            Future<?> holder = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.update(holdSql);
                held.countDown();
                try {
                    commit.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(held.await(20, TimeUnit.SECONDS)).isTrue();
            Future<?> blocked = executor.submit(action);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE datname = current_database() AND cardinality(pg_blocking_pids(pid)) > 0", Integer.class) == 0) {
                assertThat(blocked.isDone()).as("the statement under test never waited for the row").isFalse();
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.sleep(5);
            }
            commit.countDown();
            holder.get(20, TimeUnit.SECONDS);
            blocked.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private int underWay(String key) {
        return jdbc.queryForList("SELECT pending FROM login_throttle WHERE throttle_key = ?", Integer.class, key)
                .stream().findFirst().orElse(0);
    }

    private int failures(String key) {
        return jdbc.queryForList("SELECT failures FROM login_throttle WHERE throttle_key = ?", Integer.class, key)
                .stream().findFirst().orElse(0);
    }

    private void failTimesAndLockAt(String key, int limit) {
        for (int i = 1; i < limit; i++) {
            limiter.begin(new LoginAttemptLimiter.Limit(key, limit)).failed();
        }
        assertThatThrownBy(() -> limiter.begin(new LoginAttemptLimiter.Limit(key, limit)).failed())
                .isInstanceOf(TooManyLoginAttemptsException.class);
    }

    private void failTimes(String key, int times) {
        for (int i = 0; i < times; i++) {
            limiter.recordFailure(key);
        }
    }

    private void failTimesAndLock(String key) {
        assertThatThrownBy(() -> limiter.recordFailure(key)).isInstanceOf(TooManyLoginAttemptsException.class);
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
