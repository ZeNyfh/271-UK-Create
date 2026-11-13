package com.ukgeo.realtimelocalisedweather.compat.sereneseasons;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Pure wall-clock math for mapping real-life time onto Serene Seasons cycle ticks.
 *
 * <p>Full year = {@code 4 * daysPerSeason} real days. Continuous progress through
 * Early→Mid→Late within each major season is achieved by mapping the year fraction
 * onto {@code cycleDurationTicks}.
 */
public final class RealtimeSeasonClock {
    private static final long MILLIS_PER_DAY = 24L * 60L * 60L * 1000L;
    private static final int MAJOR_SEASONS = 4;

    private RealtimeSeasonClock() {
    }

    /** Floors {@code epochMillis} to local midnight in {@code zone}. */
    public static long anchorToStartOfDay(long epochMillis, ZoneId zone) {
        ZonedDateTime zoned = Instant.ofEpochMilli(epochMillis).atZone(zone);
        return zoned.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /**
     * Maps elapsed wall-clock time since {@code anchorMillis} onto Serene cycle ticks.
     *
     * <pre>
     * yearMillis = daysPerSeason * 4 * 86_400_000
     * intoYear   = floorMod(now - anchor, yearMillis)
     * cycleTicks = (intoYear * cycleDurationTicks) / yearMillis
     * </pre>
     */
    public static int computeCycleTicks(long nowMillis, long anchorMillis, int daysPerSeason, int cycleDurationTicks) {
        if (daysPerSeason <= 0 || cycleDurationTicks <= 0) {
            return 0;
        }
        long yearMillis = yearDurationMillis(daysPerSeason);
        long intoYear = Math.floorMod(nowMillis - anchorMillis, yearMillis);
        return (int) ((intoYear * (long) cycleDurationTicks) / yearMillis);
    }

    /** Milliseconds until the next major-season boundary (every {@code daysPerSeason} days from anchor). */
    public static long millisUntilNextSeasonBoundary(long nowMillis, long anchorMillis, int daysPerSeason) {
        if (daysPerSeason <= 0) {
            return 0L;
        }
        long seasonMillis = daysPerSeason * MILLIS_PER_DAY;
        long intoSeason = Math.floorMod(nowMillis - anchorMillis, seasonMillis);
        return seasonMillis - intoSeason;
    }

    /** Major season index 0=Spring … 3=Winter, looping every full year. */
    public static int majorSeasonIndex(long nowMillis, long anchorMillis, int daysPerSeason) {
        if (daysPerSeason <= 0) {
            return 0;
        }
        long seasonMillis = daysPerSeason * MILLIS_PER_DAY;
        long elapsed = nowMillis - anchorMillis;
        return (int) Math.floorMod(elapsed / seasonMillis, MAJOR_SEASONS);
    }

    public static long yearDurationMillis(int daysPerSeason) {
        return daysPerSeason * (long) MAJOR_SEASONS * MILLIS_PER_DAY;
    }
}
