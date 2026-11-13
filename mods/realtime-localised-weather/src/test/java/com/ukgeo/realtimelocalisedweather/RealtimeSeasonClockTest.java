package com.ukgeo.realtimelocalisedweather;

import com.ukgeo.realtimelocalisedweather.compat.sereneseasons.RealtimeSeasonClock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class RealtimeSeasonClockTest {
    private static final int DAYS_PER_SEASON = 7;
    private static final int CYCLE_DURATION_TICKS = 2_304_000; // Serene default: 24000 * 8 * 12
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    @Test
    void atAnchorCycleTicksAreZero() {
        long anchor = RealtimeSeasonClock.anchorToStartOfDay(1_700_000_000_000L, LONDON);
        assertEquals(0, RealtimeSeasonClock.computeCycleTicks(anchor, anchor, DAYS_PER_SEASON, CYCLE_DURATION_TICKS));
    }

    @Test
    void afterSevenDaysIsEarlySummerQuarter() {
        long anchor = 0L;
        int ticks = RealtimeSeasonClock.computeCycleTicks(7L * DAY_MS, anchor, DAYS_PER_SEASON, CYCLE_DURATION_TICKS);
        assertEquals(CYCLE_DURATION_TICKS / 4, ticks);
    }

    @Test
    void afterFourteenDaysIsEarlyAutumnHalf() {
        long anchor = 0L;
        int ticks = RealtimeSeasonClock.computeCycleTicks(14L * DAY_MS, anchor, DAYS_PER_SEASON, CYCLE_DURATION_TICKS);
        assertEquals(CYCLE_DURATION_TICKS / 2, ticks);
    }

    @Test
    void midWeekProgressIncreasesTicksGradually() {
        long anchor = 0L;
        int early = RealtimeSeasonClock.computeCycleTicks(DAY_MS, anchor, DAYS_PER_SEASON, CYCLE_DURATION_TICKS);
        int mid = RealtimeSeasonClock.computeCycleTicks(3L * DAY_MS, anchor, DAYS_PER_SEASON, CYCLE_DURATION_TICKS);
        int late = RealtimeSeasonClock.computeCycleTicks(6L * DAY_MS, anchor, DAYS_PER_SEASON, CYCLE_DURATION_TICKS);
        assertTrue(early > 0);
        assertTrue(mid > early);
        assertTrue(late > mid);
        assertTrue(late < CYCLE_DURATION_TICKS / 4);
    }

    @Test
    void loopsAfterTwentyEightDays() {
        long anchor = 0L;
        int atStart = RealtimeSeasonClock.computeCycleTicks(anchor, anchor, DAYS_PER_SEASON, CYCLE_DURATION_TICKS);
        int afterYear = RealtimeSeasonClock.computeCycleTicks(28L * DAY_MS, anchor, DAYS_PER_SEASON, CYCLE_DURATION_TICKS);
        int afterYearPlusDay = RealtimeSeasonClock.computeCycleTicks(29L * DAY_MS, anchor, DAYS_PER_SEASON, CYCLE_DURATION_TICKS);
        assertEquals(atStart, afterYear);
        assertEquals(
            RealtimeSeasonClock.computeCycleTicks(DAY_MS, anchor, DAYS_PER_SEASON, CYCLE_DURATION_TICKS),
            afterYearPlusDay
        );
    }

    @Test
    void anchorFloorsToStartOfDayInTimezone() {
        long afternoon = ZonedDateTime.of(2024, 6, 15, 14, 30, 0, 0, ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli();
        long anchored = RealtimeSeasonClock.anchorToStartOfDay(afternoon, LONDON);
        long expected = LocalDate.of(2024, 6, 15)
            .atStartOfDay(LONDON)
            .toInstant()
            .toEpochMilli();
        assertEquals(expected, anchored);
        assertTrue(anchored < afternoon);
    }

    @Test
    void millisUntilNextSeasonBoundary() {
        long anchor = 0L;
        assertEquals(7L * DAY_MS, RealtimeSeasonClock.millisUntilNextSeasonBoundary(0L, anchor, DAYS_PER_SEASON));
        assertEquals(DAY_MS, RealtimeSeasonClock.millisUntilNextSeasonBoundary(6L * DAY_MS, anchor, DAYS_PER_SEASON));
    }
}
