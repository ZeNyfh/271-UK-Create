package com.ukgeo.realtimelocalisedweather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ukgeo.realtimelocalisedweather.client.render.LocalisedPrecipitationRenderer;
import com.ukgeo.realtimelocalisedweather.client.render.PrecipitationCoverage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Spatial coverage metrics for jittered-hex precipitation selection.
 * Weather-sample / interpolatedRate gating remains separate: intensity==0 means true dry.
 */
class PrecipitationCoverageTest {
    private static final float[] INTENSITIES = {
        0.01F, 0.05F, 0.10F, 0.20F, 0.30F, 0.40F, 0.50F, 0.75F, 1.00F
    };

    private static final class Metrics {
        final int selected;
        final int total;
        final float maxEmptyDist;
        final float avgSelectedNn;

        Metrics(int selected, int total, float maxEmptyDist, float avgSelectedNn) {
            this.selected = selected;
            this.total = total;
            this.maxEmptyDist = maxEmptyDist;
            this.avgSelectedNn = avgSelectedNn;
        }
    }

    private static boolean selected(int x, int z, float intensity) {
        return PrecipitationCoverage.shouldRenderColumn(x, z, intensity);
    }

    /** Old Bernoulli thinning (without the 0.03 hard cutoff) for gap comparison. */
    private static boolean oldHashSelect(int x, int z, float intensity) {
        return LocalisedPrecipitationRenderer.densityHash(x, z) <= intensity;
    }

    private static Metrics computeMetrics(int radius, float intensity, boolean useOld) {
        int min = -radius;
        int max = radius;
        int side = max - min + 1;
        boolean[][] grid = new boolean[side][side];
        List<int[]> points = new ArrayList<>();
        int count = 0;
        for (int z = min; z <= max; z++) {
            for (int x = min; x <= max; x++) {
                boolean on = useOld ? oldHashSelect(x, z, intensity) : selected(x, z, intensity);
                int ix = x - min;
                int iz = z - min;
                grid[iz][ix] = on;
                if (on) {
                    count++;
                    points.add(new int[] {x, z});
                }
            }
        }

        float maxEmpty = 0.0F;
        if (count == 0) {
            maxEmpty = Float.POSITIVE_INFINITY;
        } else {
            for (int z = min; z <= max; z++) {
                for (int x = min; x <= max; x++) {
                    if (grid[z - min][x - min]) {
                        continue;
                    }
                    float best = Float.POSITIVE_INFINITY;
                    for (int[] p : points) {
                        float dx = x - p[0];
                        float dz = z - p[1];
                        float d = (float) Math.sqrt(dx * dx + dz * dz);
                        if (d < best) {
                            best = d;
                        }
                    }
                    if (best > maxEmpty) {
                        maxEmpty = best;
                    }
                }
            }
        }

        float avgNn = Float.NaN;
        if (points.size() >= 2) {
            double sum = 0.0;
            for (int i = 0; i < points.size(); i++) {
                float best = Float.POSITIVE_INFINITY;
                int[] a = points.get(i);
                for (int j = 0; j < points.size(); j++) {
                    if (i == j) {
                        continue;
                    }
                    int[] b = points.get(j);
                    float dx = a[0] - b[0];
                    float dz = a[1] - b[1];
                    float d = (float) Math.sqrt(dx * dx + dz * dz);
                    if (d < best) {
                        best = d;
                    }
                }
                sum += best;
            }
            avgNn = (float) (sum / points.size());
        } else if (points.size() == 1) {
            avgNn = Float.POSITIVE_INFINITY;
        }

        return new Metrics(count, side * side, maxEmpty, avgNn);
    }

    @Test
    void coverageAndAlphaCurves() {
        assertEquals(0.0F, PrecipitationCoverage.coverageFraction(0.0F), 1.0e-6F);
        assertEquals(0.0F, PrecipitationCoverage.coverageFraction(-1.0F), 1.0e-6F);
        assertTrue(PrecipitationCoverage.coverageFraction(0.01F) >= PrecipitationCoverage.MIN_POSITIVE_COVERAGE - 1.0e-4F);
        assertTrue(PrecipitationCoverage.coverageFraction(0.01F) < PrecipitationCoverage.coverageFraction(0.5F));
        assertTrue(PrecipitationCoverage.coverageFraction(1.0F) >= 0.999F);

        float a01 = PrecipitationCoverage.alphaFactor(0.01F);
        float a50 = PrecipitationCoverage.alphaFactor(0.5F);
        float a100 = PrecipitationCoverage.alphaFactor(1.0F);
        assertTrue(a01 < a50, "alphaFactor(0.01) < alphaFactor(0.5)");
        assertTrue(a50 < a100, "alphaFactor(0.5) < alphaFactor(1.0)");
        assertTrue(a01 >= 0.15F && a01 <= 0.30F, "1% drizzle alpha stays faint: " + a01);
        assertTrue(a100 >= 0.95F, "full intensity alpha near 1: " + a100);
    }

    @Test
    void intensityZeroSelectsNothing() {
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                assertFalse(selected(x, z, 0.0F));
                assertFalse(LocalisedPrecipitationRenderer.passesDensityFilter(x, z, 0.0F));
            }
        }
    }

    @Test
    void determinismAndCameraIndependence() {
        int x = 123;
        int z = -456;
        float intensity = 0.12F;
        boolean a = selected(x, z, intensity);
        boolean b = selected(x, z, intensity);
        boolean c = PrecipitationCoverage.shouldRenderColumn(x, z, intensity);
        assertEquals(a, b);
        assertEquals(a, c);
        // Selection is world-xz only — no camera parameters exist on the API.
        assertEquals(a, LocalisedPrecipitationRenderer.passesDensityFilter(x, z, intensity));
    }

    @Test
    void spatialMetricsFancyAndFast() {
        // Fancy radius 10 → 21×21; fast radius 5 → 11×11.
        int[] radii = {10, 5};
        String[] labels = {"fancy21", "fast11"};

        System.out.println("intensity | grid | selected | frac | maxEmpty | avgSelNN | coverage | alpha");
        for (int r = 0; r < radii.length; r++) {
            int radius = radii[r];
            int prevCount = -1;
            Float prevAvgNn = null;
            Metrics m01 = null;
            Metrics m10 = null;

            for (float intensity : INTENSITIES) {
                Metrics m = computeMetrics(radius, intensity, false);
                if (intensity == 0.01F) {
                    m01 = m;
                }
                if (intensity == 0.10F) {
                    m10 = m;
                }

                System.out.printf(
                    Locale.ROOT,
                    "%8.2f | %s | %4d/%4d | %5.1f%% | %8.2f | %8.2f | %6.3f | %5.3f%n",
                    intensity,
                    labels[r],
                    m.selected,
                    m.total,
                    100.0F * m.selected / m.total,
                    m.maxEmptyDist,
                    m.avgSelectedNn,
                    PrecipitationCoverage.coverageFraction(intensity),
                    PrecipitationCoverage.alphaFactor(intensity)
                );

                assertTrue(m.selected >= 0);
                if (intensity > 0.0F) {
                    assertTrue(m.selected > 4,
                        labels[r] + " intensity " + intensity + " must be substantial, got " + m.selected);
                }
                if (intensity >= 0.999F) {
                    assertTrue(m.selected >= m.total * 99 / 100,
                        labels[r] + " intensity 1.0 nearly all: " + m.selected + "/" + m.total);
                }
                if (prevCount >= 0) {
                    assertTrue(m.selected >= prevCount,
                        labels[r] + " counts non-decreasing: " + prevCount + " -> " + m.selected);
                }
                prevCount = m.selected;

                if (prevAvgNn != null && !Float.isNaN(m.avgSelectedNn) && !Float.isInfinite(m.avgSelectedNn)
                    && !Float.isInfinite(prevAvgNn)) {
                    // Average NN among selected should not grow materially as intensity rises.
                    assertTrue(m.avgSelectedNn <= prevAvgNn + 0.75F,
                        labels[r] + " avg NN should decrease or stay reasonable: "
                            + prevAvgNn + " -> " + m.avgSelectedNn);
                }
                if (!Float.isNaN(m.avgSelectedNn) && !Float.isInfinite(m.avgSelectedNn)) {
                    prevAvgNn = m.avgSelectedNn;
                }
            }

            assertTrue(m01 != null && m01.selected > 4, "0.01 must keep substantial columns");
            assertTrue(m01.maxEmptyDist <= 5.5F,
                labels[r] + " max empty at 0.01 must be bounded (<=5.5), got " + m01.maxEmptyDist);
            assertTrue(m10.maxEmptyDist <= 5.0F,
                labels[r] + " max empty at 0.10 must be bounded (<=5), got " + m10.maxEmptyDist);

            // Old hash<=0.01 leaves larger voids than the new lattice (documented regression).
            Metrics old01 = computeMetrics(radius, 0.01F, true);
            assertTrue(Float.isInfinite(old01.maxEmptyDist) || old01.maxEmptyDist > m01.maxEmptyDist,
                labels[r] + " old hash-at-0.01 maxEmpty (" + old01.maxEmptyDist
                    + ") should exceed new (" + m01.maxEmptyDist + "); old selected=" + old01.selected);
            System.out.printf(
                Locale.ROOT,
                "  compare old hash@0.01 %s: selected=%d maxEmpty=%s vs new selected=%d maxEmpty=%.2f%n",
                labels[r],
                old01.selected,
                Float.isInfinite(old01.maxEmptyDist) ? "inf" : String.format(Locale.ROOT, "%.2f", old01.maxEmptyDist),
                m01.selected,
                m01.maxEmptyDist
            );
        }
    }

    @Test
    void notObviousRegularGrid() {
        float intensity = 0.08F;
        int grid = 32;
        int selected = 0;
        int onStride2 = 0;
        int offStride2 = 0;
        for (int x = 0; x < grid; x++) {
            for (int z = 0; z < grid; z++) {
                if (!selected(x, z, intensity)) {
                    continue;
                }
                selected++;
                if ((x % 2 == 0) && (z % 2 == 0)) {
                    onStride2++;
                } else {
                    offStride2++;
                }
            }
        }
        assertTrue(selected > 0);
        assertTrue(offStride2 > 0, "jitter must place columns off a strict even lattice");
        // Not an exclusive even-grid quota: both parity classes appear.
        assertTrue(onStride2 > 0 && offStride2 > selected / 10);
    }
}
