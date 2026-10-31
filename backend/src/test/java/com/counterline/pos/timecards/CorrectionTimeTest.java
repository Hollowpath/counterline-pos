package com.counterline.pos.timecards;

import com.counterline.pos.common.BadRequestException;
import com.counterline.pos.timecards.TimecardCorrectionService.CloseShift;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A correction's local time is read in the business zone, and a wall-clock time the zone does not have exactly once (the
 * clocks went forward over it, or back so that it happens twice) is refused rather than moved to one of its readings.
 * New York stands in for a zone with daylight saving; the default zone has none.
 */
class CorrectionTimeTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final ZoneId KUALA_LUMPUR = ZoneId.of("Asia/Kuala_Lumpur");

    private static CloseShift local(LocalDateTime time) {
        return new CloseShift(null, time, "Forgot");
    }

    @Test
    void anOrdinaryLocalTimeIsReadInTheZone() {
        assertThat(local(LocalDateTime.of(2026, 10, 12, 10, 0)).clockOut(KUALA_LUMPUR)).isEqualTo(Instant.parse("2026-10-12T02:00:00Z"));
        assertThat(local(LocalDateTime.of(2026, 7, 1, 9, 0)).clockOut(NEW_YORK)).isEqualTo(Instant.parse("2026-07-01T13:00:00Z"));
        // The hours either side of a change are not ambiguous.
        assertThat(local(LocalDateTime.of(2026, 3, 8, 1, 59)).clockOut(NEW_YORK)).isEqualTo(Instant.parse("2026-03-08T06:59:00Z"));
        assertThat(local(LocalDateTime.of(2026, 3, 8, 3, 0)).clockOut(NEW_YORK)).isEqualTo(Instant.parse("2026-03-08T07:00:00Z"));
        assertThat(local(LocalDateTime.of(2026, 11, 1, 0, 59)).clockOut(NEW_YORK)).isEqualTo(Instant.parse("2026-11-01T04:59:00Z"));
        assertThat(local(LocalDateTime.of(2026, 11, 1, 2, 0)).clockOut(NEW_YORK)).isEqualTo(Instant.parse("2026-11-01T07:00:00Z"));
    }

    @Test
    void aTimeTheClocksSkippedIsRefusedNotMovedForward() {
        // 2026-03-08: 02:00 became 03:00 in New York, so 02:30 never happened.
        assertThatThrownBy(() -> local(LocalDateTime.of(2026, 3, 8, 2, 30)).clockOut(NEW_YORK))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("That time does not exist in America/New_York (the clocks went forward). Choose another time.");
        assertThatThrownBy(() -> local(LocalDateTime.of(2026, 3, 8, 2, 0)).clockOut(NEW_YORK)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void aTimeThatHappenedTwiceIsRefusedNotGivenTheEarlierReading() {
        // 2026-11-01: 02:00 became 01:00 in New York, so 01:30 happened twice.
        assertThatThrownBy(() -> local(LocalDateTime.of(2026, 11, 1, 1, 30)).clockOut(NEW_YORK))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("That time happens twice in America/New_York (the clocks went back). Choose a time outside that hour.");
        assertThatThrownBy(() -> local(LocalDateTime.of(2026, 11, 1, 1, 0)).clockOut(NEW_YORK)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void anExactInstantIsNeverQuestionedAndAZoneWithoutDaylightSavingHasNoSuchTimes() {
        Instant moment = Instant.parse("2026-11-01T05:30:00Z");
        assertThat(new CloseShift(moment, null, "Exact").clockOut(NEW_YORK)).isEqualTo(moment);
        for (int hour = 0; hour < 24; hour++) {
            assertThat(local(LocalDateTime.of(2026, 3, 8, hour, 30)).clockOut(KUALA_LUMPUR)).isNotNull();
            assertThat(local(LocalDateTime.of(2026, 11, 1, hour, 30)).clockOut(KUALA_LUMPUR)).isNotNull();
        }
    }
}
