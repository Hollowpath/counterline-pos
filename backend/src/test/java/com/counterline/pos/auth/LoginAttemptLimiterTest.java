package com.counterline.pos.auth;

import com.counterline.pos.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

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
        limiter = new LoginAttemptLimiter(jdbc, transactions,
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
                .isInstanceOfSatisfying(TooManyLoginAttemptsException.class, ex -> {
                    assertThat(ex.retryAfterSeconds()).isEqualTo(300);
                    assertThat(ex.managerCanUnlock()).isTrue();
                });
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
    void aSuccessOrAnUnlockStartsTheCountAgain() {
        failTimes("till:1", 8);
        limiter.recordSuccess("till:1");
        assertThat(limiter.recordFailure("till:1")).isEqualTo(9);

        failTimes("till:1", 8);
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
                .isEqualTo("Invalid PIN. 3 tries left before the PIN pad locks for 5 minutes.");
        assertThat(limiter.withTriesLeft("Enter a manager PIN to continue.", 1))
                .isEqualTo("Enter a manager PIN to continue. 1 try left before the PIN pad locks for 5 minutes.");
    }

    @Test
    void aClientAddressIsNotAPadAManagerCanUnlock() {
        failTimes("dashboard:10.0.0.1", 9);
        assertThatThrownBy(() -> limiter.recordFailure("dashboard:10.0.0.1"))
                .isInstanceOfSatisfying(TooManyLoginAttemptsException.class, ex -> assertThat(ex.managerCanUnlock()).isFalse());
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
