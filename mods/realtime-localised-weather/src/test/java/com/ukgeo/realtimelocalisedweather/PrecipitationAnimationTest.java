package com.ukgeo.realtimelocalisedweather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ukgeo.realtimelocalisedweather.client.render.LocalisedPrecipitationRenderer;
import com.ukgeo.realtimelocalisedweather.client.render.PrecipitationAnimation;
import org.junit.jupiter.api.Test;

class PrecipitationAnimationTest {
    private static final float EPS = 1.0e-5F;

    /** Old buggy formula: adding unbounded hash to float weatherTime destroys mantissa. */
    private static float oldBuggyFrac(float weatherTime, int worldX, int worldZ, boolean snow) {
        float phase = (weatherTime + PrecipitationAnimation.hash(worldX, worldZ)) * PrecipitationAnimation.speed(snow);
        return phase - (float) Math.floor(phase);
    }

    private static float fracDelta(float a, float b) {
        float d = b - a;
        d -= (float) Math.floor(d);
        if (d > 0.5F) {
            d -= 1.0F;
        }
        return d;
    }

    @Test
    void sameCoordsDifferentGameTicksAdvancesPhase() {
        int x = 12;
        int z = -7;
        float t0 = PrecipitationAnimation.weatherTime(100L, 0.0F);
        float t1 = PrecipitationAnimation.weatherTime(101L, 0.0F);
        float p0 = PrecipitationAnimation.scrollPhase(t0, x, z, false);
        float p1 = PrecipitationAnimation.scrollPhase(t1, x, z, false);
        assertTrue(Math.abs(fracDelta(p0, p1)) > EPS, "phase should advance one tick");
        assertEquals(PrecipitationAnimation.RAIN_SPEED, Math.abs(fracDelta(p0, p1)), 1.0e-4F);
    }

    @Test
    void sameCoordsDifferentPartialTicksAdvancesSmoothly() {
        int x = 3;
        int z = 9;
        float t0 = PrecipitationAnimation.weatherTime(50L, 0.0F);
        float t1 = PrecipitationAnimation.weatherTime(50L, 0.5F);
        float p0 = PrecipitationAnimation.scrollPhase(t0, x, z, false);
        float p1 = PrecipitationAnimation.scrollPhase(t1, x, z, false);
        assertEquals(0.5F * PrecipitationAnimation.RAIN_SPEED, Math.abs(fracDelta(p0, p1)), 1.0e-4F);
    }

    @Test
    void largePositiveWorldCoordsAnimationAdvances() {
        int x = 1_000_000;
        int z = 2_000_000;
        float t0 = PrecipitationAnimation.weatherTime(10L, 0.0F);
        float t1 = PrecipitationAnimation.weatherTime(11L, 0.0F);
        float new0 = PrecipitationAnimation.scrollPhase(t0, x, z, false);
        float new1 = PrecipitationAnimation.scrollPhase(t1, x, z, false);
        assertTrue(Math.abs(fracDelta(new0, new1)) > EPS, "new formula advances at large +coords");

        float old0 = oldBuggyFrac(t0, x, z, false);
        float old1 = oldBuggyFrac(t1, x, z, false);
        assertEquals(0.0F, Math.abs(fracDelta(old0, old1)), EPS,
            "old formula should freeze at large +coords (documents the bug)");
    }

    @Test
    void largeNegativeWorldCoordsAnimationAdvances() {
        int x = -1_500_000;
        int z = -900_000;
        float t0 = PrecipitationAnimation.weatherTime(20L, 0.25F);
        float t1 = PrecipitationAnimation.weatherTime(21L, 0.25F);
        float new0 = PrecipitationAnimation.scrollPhase(t0, x, z, false);
        float new1 = PrecipitationAnimation.scrollPhase(t1, x, z, false);
        assertTrue(Math.abs(fracDelta(new0, new1)) > EPS, "new formula advances at large -coords");

        float old0 = oldBuggyFrac(t0, x, z, false);
        float old1 = oldBuggyFrac(t1, x, z, false);
        assertEquals(0.0F, Math.abs(fracDelta(old0, old1)), EPS,
            "old formula should freeze at large -coords (documents the bug)");
    }

    @Test
    void differentColumnsHaveDifferentPhaseOffsets() {
        float o1 = PrecipitationAnimation.phaseOffset(0, 0);
        float o2 = PrecipitationAnimation.phaseOffset(4, 0);
        float o3 = PrecipitationAnimation.phaseOffset(0, 4);
        assertTrue(o1 >= 0.0F && o1 < 1.0F);
        assertTrue(o2 >= 0.0F && o2 < 1.0F);
        assertTrue(o3 >= 0.0F && o3 < 1.0F);
        assertNotEquals(o1, o2, EPS);
        assertNotEquals(o1, o3, EPS);
    }

    @Test
    void wraparoundAtWeatherTimePeriodHasNoDiscontinuity() {
        long period = PrecipitationAnimation.WEATHER_TIME_PERIOD;
        // period*speed is designed to be an integer; float rounding leaves ~1e-5 residual.
        assertEquals(0.0, (period * (double) PrecipitationAnimation.RAIN_SPEED) % 1.0, 1.0e-4);
        assertEquals(0.0, (period * (double) PrecipitationAnimation.SNOW_SPEED) % 1.0, 1.0e-4);

        float before = PrecipitationAnimation.weatherTime(period - 1L, 0.75F);
        float after = PrecipitationAnimation.weatherTime(period, 0.0F);
        float rainBefore = PrecipitationAnimation.scrollPhase(before, 8, 16, false);
        float rainAfter = PrecipitationAnimation.scrollPhase(after, 8, 16, false);
        // Because period*speed is an integer, continuing 0.25 ticks past (period-1)+0.75
        // matches the wrapped clock at period+0.0.
        float continued = PrecipitationAnimation.scrollPhase(before + 0.25F, 8, 16, false);
        assertEquals(continued, rainAfter, EPS);

        float snowBefore = PrecipitationAnimation.scrollPhase(before, 8, 16, true);
        float snowContinued = PrecipitationAnimation.scrollPhase(before + 0.25F, 8, 16, true);
        float snowAfter = PrecipitationAnimation.scrollPhase(after, 8, 16, true);
        assertEquals(snowContinued, snowAfter, EPS);
        assertNotEquals(rainBefore, rainAfter, EPS);
        assertNotEquals(snowBefore, snowAfter, EPS);
    }

    @Test
    void intensityDoesNotAffectAnimationSpeed() {
        // scrollPhase has no intensity parameter — rain speed is constant regardless of density.
        assertEquals(PrecipitationAnimation.RAIN_SPEED, PrecipitationAnimation.speed(false), EPS);
        assertEquals(PrecipitationAnimation.SNOW_SPEED, PrecipitationAnimation.speed(true), EPS);
        assertTrue(PrecipitationAnimation.SNOW_SPEED < PrecipitationAnimation.RAIN_SPEED);

        float t0 = PrecipitationAnimation.weatherTime(0L, 0.0F);
        float t1 = PrecipitationAnimation.weatherTime(1L, 0.0F);
        float dRain = Math.abs(fracDelta(
            PrecipitationAnimation.scrollPhase(t0, 1, 2, false),
            PrecipitationAnimation.scrollPhase(t1, 1, 2, false)
        ));
        float dSnow = Math.abs(fracDelta(
            PrecipitationAnimation.scrollPhase(t0, 1, 2, true),
            PrecipitationAnimation.scrollPhase(t1, 1, 2, true)
        ));
        assertEquals(PrecipitationAnimation.RAIN_SPEED, dRain, 1.0e-4F);
        assertEquals(PrecipitationAnimation.SNOW_SPEED, dSnow, 1.0e-4F);
    }

    @Test
    void longRunningGameTimeStillAdvancesWithoutPrecisionLoss() {
        long large = 10_000_000L;
        float t0 = PrecipitationAnimation.weatherTime(large, 0.0F);
        float t1 = PrecipitationAnimation.weatherTime(large + 1L, 0.0F);
        assertTrue(t0 < PrecipitationAnimation.WEATHER_TIME_PERIOD + 1.0F);
        float p0 = PrecipitationAnimation.scrollPhase(t0, 100, -50, false);
        float p1 = PrecipitationAnimation.scrollPhase(t1, 100, -50, false);
        assertEquals(PrecipitationAnimation.RAIN_SPEED, Math.abs(fracDelta(p0, p1)), 1.0e-4F);
    }

    @Test
    void densityFilterCountsIncreaseWithIntensity() {
        float[] intensities = {0.05F, 0.15F, 0.30F, 0.60F, 1.00F};
        int[] counts = new int[intensities.length];
        int grid = 64;
        for (int i = 0; i < intensities.length; i++) {
            int count = 0;
            for (int x = 0; x < grid; x++) {
                for (int z = 0; z < grid; z++) {
                    if (LocalisedPrecipitationRenderer.passesDensityFilter(x, z, intensities[i])) {
                        count++;
                    }
                }
            }
            counts[i] = count;
        }
        for (int i = 1; i < counts.length; i++) {
            assertTrue(counts[i] > counts[i - 1],
                "counts must strictly increase: " + counts[i - 1] + " -> " + counts[i]
                    + " at intensity " + intensities[i]);
        }
        assertTrue(counts[0] > 0, "light drizzle (0.05) should be sparse but non-zero");
        assertTrue(counts[0] < grid * grid / 4, "light drizzle should remain sparse");
        assertTrue(counts[counts.length - 1] > grid * grid / 2, "max intensity (1.00) should be dense");
    }

    @Test
    void verticalUvMapsHigherWorldYToLargerV() {
        float scroll = 0.3F;
        float low = PrecipitationAnimation.verticalUv(64.0F, scroll);
        float high = PrecipitationAnimation.verticalUv(80.0F, scroll);
        assertTrue(high > low);
    }
}
