package com.ukgeo.realtimelocalisedweather.client.render;

/**
 * Separates precipitation intensity into spatial coverage and visual alpha.
 * Uses a jittered hexagonal world-space lattice so low intensities keep bounded dry
 * corridors instead of independent Bernoulli voids.
 */
public final class PrecipitationCoverage {
    /** Floor for any positive intensity — drizzle still fills ~30% of columns. */
    public static final float MIN_POSITIVE_COVERAGE = 0.30F;
    /** Intensity at which coverage reaches ~1.0 (40%+ weather stays dense). */
    public static final float FULL_COVERAGE_AT = 0.45F;
    /** Jitter amplitude as a fraction of lattice spacing (breaks grid look). */
    private static final float JITTER_STRENGTH = 0.72F;
    private static final float SQRT3_OVER_2 = 0.8660254037844386F;

    private PrecipitationCoverage() {
    }

    /**
     * Fraction of columns that should be selected at this intensity.
     * {@code intensity <= 0} → true dry (0). Any positive intensity → at least
     * {@link #MIN_POSITIVE_COVERAGE}, rising to ~1 by {@link #FULL_COVERAGE_AT}.
     */
    public static float coverageFraction(float intensity) {
        if (intensity <= 0.0F) {
            return 0.0F;
        }
        float t = clamp01(intensity / FULL_COVERAGE_AT);
        float eased = smoothstep(0.0F, 1.0F, t);
        return lerp(MIN_POSITIVE_COVERAGE, 1.0F, eased);
    }

    /**
     * Multiplier for column alpha. Low intensity → faint drizzle; high → near full.
     */
    public static float alphaFactor(float intensity) {
        return lerp(0.18F, 1.0F, (float) Math.sqrt(clamp01(intensity)));
    }

    /**
     * World-anchored, deterministic column selection. Camera-stable (xz only).
     * Jittered hex lattice sized by {@code spacing = 1/sqrt(coverage)} bounds the
     * largest empty region to O(spacing).
     */
    public static boolean shouldRenderColumn(int worldX, int worldZ, float intensity) {
        float coverage = coverageFraction(intensity);
        if (coverage <= 0.0F) {
            return false;
        }
        // Near-full coverage (intensity ~40%+ with FULL_COVERAGE_AT) → every column.
        // Lattice with spacing≈1 still leaves holes under strong jitter, so threshold early.
        if (coverage >= 0.95F) {
            return true;
        }

        float spacing = 1.0F / (float) Math.sqrt(coverage);

        float rowHeight = spacing * SQRT3_OVER_2;
        float px = worldX + 0.5F;
        float pz = worldZ + 0.5F;

        int jCenter = Math.round(pz / rowHeight);
        int iCenter = Math.round(px / spacing - ((jCenter & 1) * 0.5F));

        // Search a neighbourhood large enough that strong jitter cannot miss claimants.
        for (int dj = -2; dj <= 2; dj++) {
            for (int di = -2; di <= 2; di++) {
                int j = jCenter + dj;
                int i = iCenter + di;
                float baseX = (i + ((j & 1) * 0.5F)) * spacing;
                float baseZ = j * rowHeight;

                float n0 = interleavedGradientNoise(i, j);
                float n1 = interleavedGradientNoise(i + 19.0F, j + 47.0F);
                float jx = (n0 - 0.5F) * spacing * JITTER_STRENGTH;
                float jz = (n1 - 0.5F) * spacing * JITTER_STRENGTH;

                int claimX = Math.round(baseX + jx);
                int claimZ = Math.round(baseZ + jz);
                if (claimX == worldX && claimZ == worldZ) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Jorge Jimenez interleaved gradient noise → [0, 1). */
    public static float interleavedGradientNoise(float x, float y) {
        return fract(52.9829189F * fract(x * 0.06711056F + y * 0.00583715F));
    }

    public static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3.0F - 2.0F * t);
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    public static float clamp01(float v) {
        if (v <= 0.0F) {
            return 0.0F;
        }
        if (v >= 1.0F) {
            return 1.0F;
        }
        return v;
    }

    private static float fract(float v) {
        return v - (float) Math.floor(v);
    }
}
