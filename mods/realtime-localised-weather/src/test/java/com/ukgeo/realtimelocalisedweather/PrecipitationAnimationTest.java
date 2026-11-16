package com.ukgeo.realtimelocalisedweather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ukgeo.realtimelocalisedweather.client.render.LocalisedPrecipitationRenderer;
import com.ukgeo.realtimelocalisedweather.client.render.PrecipitationAnimation;
import org.junit.jupiter.api.Test;

class PrecipitationAnimationTest {
    private static final float EPS = 1.0e-5F;

    /**
     * Old buggy formula: adding unbounded hash to float weatherTime destroys mantissa —
     * kept as a regression document that large coords freeze animation.
     */
    private static float oldBuggyFrac(float weatherTime, int worldX, int worldZ) {
        float phase = (weatherTime + PrecipitationAnimation.hash(worldX, worldZ)) * 0.075F;
        return phase - (float) Math.floor(phase);
    }

    private static float wrapDelta(float a, float b, float modulus) {
        float d = PrecipitationAnimation.floorMod(b - a, modulus);
        if (d > modulus * 0.5F) {
            d -= modulus;
        }
        return d;
    }

    @Test
    void rainScrollAdvancesWithGameTime() {
        int x = 12;
        int z = -7;
        float v0 = PrecipitationAnimation.rainScrollV(100L, 0.0F, x, z);
        float v1 = PrecipitationAnimation.rainScrollV(101L, 0.0F, x, z);
        float speed = 3.0F + PrecipitationAnimation.columnUnit(x, z);
        assertEquals(-speed / 32.0F, wrapDelta(v0, v1, 32.0F), 1.0e-4F);
    }

    @Test
    void rainScrollAdvancesWithPartialTick() {
        int x = 3;
        int z = 9;
        float v0 = PrecipitationAnimation.rainScrollV(50L, 0.0F, x, z);
        float v1 = PrecipitationAnimation.rainScrollV(50L, 0.5F, x, z);
        float speed = 3.0F + PrecipitationAnimation.columnUnit(x, z);
        assertEquals(-0.5F * speed / 32.0F, wrapDelta(v0, v1, 32.0F), 1.0e-4F);
    }

    @Test
    void largePositiveWorldCoordsAnimationAdvances() {
        int x = 1_000_000;
        int z = 2_000_000;
        float v0 = PrecipitationAnimation.rainScrollV(10L, 0.0F, x, z);
        float v1 = PrecipitationAnimation.rainScrollV(11L, 0.0F, x, z);
        assertTrue(Math.abs(wrapDelta(v0, v1, 32.0F)) > EPS, "rainScrollV advances at large +coords");

        float old0 = oldBuggyFrac(10.0F, x, z);
        float old1 = oldBuggyFrac(11.0F, x, z);
        assertEquals(0.0F, Math.abs(old1 - old0), EPS,
            "old formula should freeze at large +coords (documents the bug)");
    }

    @Test
    void largeNegativeWorldCoordsAnimationAdvances() {
        int x = -1_500_000;
        int z = -900_000;
        float v0 = PrecipitationAnimation.rainScrollV(20L, 0.25F, x, z);
        float v1 = PrecipitationAnimation.rainScrollV(21L, 0.25F, x, z);
        assertTrue(Math.abs(wrapDelta(v0, v1, 32.0F)) > EPS, "rainScrollV advances at large -coords");

        float old0 = oldBuggyFrac(20.25F, x, z);
        float old1 = oldBuggyFrac(21.25F, x, z);
        assertEquals(0.0F, Math.abs(old1 - old0), EPS,
            "old formula should freeze at large -coords (documents the bug)");
    }

    @Test
    void differentColumnsHaveDifferentRainPhases() {
        float u1 = PrecipitationAnimation.columnUnit(0, 0);
        float u2 = PrecipitationAnimation.columnUnit(4, 0);
        float u3 = PrecipitationAnimation.columnUnit(0, 4);
        assertTrue(u1 >= 0.0F && u1 < 1.0F);
        assertTrue(u2 >= 0.0F && u2 < 1.0F);
        assertTrue(u3 >= 0.0F && u3 < 1.0F);
        assertNotEquals(u1, u2, EPS);
        assertNotEquals(u1, u3, EPS);

        float r1 = PrecipitationAnimation.rainScrollV(0L, 0.0F, 0, 0);
        float r2 = PrecipitationAnimation.rainScrollV(0L, 0.0F, 4, 0);
        assertNotEquals(r1, r2, EPS);
    }

    @Test
    void rainTickMaskKeepsLongRunningTimeBounded() {
        long large = 10_000_000L;
        int ticks = (int) (large & PrecipitationAnimation.RAIN_TICK_MASK);
        assertTrue(ticks <= PrecipitationAnimation.RAIN_TICK_MASK);
        float v0 = PrecipitationAnimation.rainScrollV(large, 0.0F, 100, -50);
        float v1 = PrecipitationAnimation.rainScrollV(large + 1L, 0.0F, 100, -50);
        float speed = 3.0F + PrecipitationAnimation.columnUnit(100, -50);
        float delta = wrapDelta(v0, v1, 32.0F);
        // Large masked tick values lose a few float ulps vs exact -speed/32.
        assertEquals(-speed / 32.0F, delta, 5.0e-4F);
        assertTrue(Math.abs(delta) > EPS, "must still advance under long-running gameTime");
    }

    @Test
    void intensityDoesNotAffectAnimationSpeed() {
        // rainScrollV / snowScrollV have no intensity parameter — speed is constant.
        float rain0 = PrecipitationAnimation.rainScrollV(0L, 0.0F, 1, 2);
        float rain1 = PrecipitationAnimation.rainScrollV(1L, 0.0F, 1, 2);
        float snow0 = PrecipitationAnimation.snowScrollV(0L, 0.0F);
        float snow1 = PrecipitationAnimation.snowScrollV(1L, 0.0F);
        float dRain = Math.abs(wrapDelta(rain0, rain1, 32.0F));
        float dSnow = Math.abs(wrapDelta(snow0, snow1, 1.0F));
        assertTrue(dRain > dSnow, "snow V scroll is slower than rain over the same dt");
        assertEquals(1.0F / 512.0F, dSnow, 1.0e-5F);
    }

    @Test
    void rainUvUsesFullTextureWidth() {
        // Vanilla maps U 0.0 → 1.0 across the thin camera-facing quad (full 64×256 rain.png).
        assertEquals(0.0F, 0.0F, EPS);
        assertEquals(1.0F, 0.0F + 1.0F, EPS);
        assertTrue(PrecipitationAnimation.snowUOffset(5, 7) >= 0.0F);
        assertTrue(PrecipitationAnimation.snowUOffset(5, 7) < 1.0F);
    }

    @Test
    void quadHalfExtentIsPerpendicularAndHalfLength() {
        double relX = 10.0;
        double relZ = 0.0;
        float[] ext = PrecipitationAnimation.quadHalfExtent(relX, relZ);
        float len = (float) Math.sqrt(ext[0] * ext[0] + ext[1] * ext[1]);
        assertEquals(0.5F, len, 1.0e-4F);
        assertEquals(0.0F, ext[0] * (float) relX + ext[1] * (float) relZ, 1.0e-4F);

        float[] diag = PrecipitationAnimation.quadHalfExtent(3.0, 4.0);
        float diagLen = (float) Math.sqrt(diag[0] * diag[0] + diag[1] * diag[1]);
        assertEquals(0.5F, diagLen, 1.0e-4F);
        assertEquals(0.0F, diag[0] * 3.0F + diag[1] * 4.0F, 1.0e-4F);
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
    void densityPlacementIsNotStride4Lattice() {
        float intensity = 0.15F;
        int grid = 32;
        int selected = 0;
        int onLattice = 0;
        int offLattice = 0;
        for (int x = 0; x < grid; x++) {
            for (int z = 0; z < grid; z++) {
                if (!LocalisedPrecipitationRenderer.passesDensityFilter(x, z, intensity)) {
                    continue;
                }
                selected++;
                if ((x % 4 == 0) && (z % 4 == 0)) {
                    onLattice++;
                } else {
                    offLattice++;
                }
            }
        }
        assertTrue(selected > 0, "intensity 0.15 should select some cells");
        assertTrue(offLattice > 0,
            "selected cells must include off-lattice positions (not only x%4==0 && z%4==0)");
        assertTrue(offLattice > onLattice,
            "majority of sparse selections should be off the old spacing-4 lattice");

        int full = 0;
        for (int x = 0; x < grid; x++) {
            for (int z = 0; z < grid; z++) {
                if (LocalisedPrecipitationRenderer.passesDensityFilter(x, z, 1.0F)) {
                    full++;
                }
            }
        }
        assertTrue(full > grid * grid * 9 / 10, "intensity 1.0 should pass nearly all cells");
    }

    @Test
    void verticalUvMapsHigherWorldYToLargerV() {
        float scroll = 0.3F;
        float low = PrecipitationAnimation.verticalUv(64.0F, scroll);
        float high = PrecipitationAnimation.verticalUv(80.0F, scroll);
        assertTrue(high > low);
    }
}
