package com.ukgeo.worldgen;

/**
 * River corridor → lake mouth blends for approach cells only.
 * Effective lake interiors use the lake path and never pass through these helpers.
 */
final class RiverLakeTransition {
    private RiverLakeTransition() {
    }

    /** 1 at the lake edge (blocks ≈ 0), 0 at/beyond {@code radius}. */
    static double mouthWeight(double lakeBlocks, double radius) {
        if (radius <= 0.0) {
            return 0.0;
        }
        if (lakeBlocks <= 0.0) {
            return 1.0;
        }
        if (lakeBlocks >= radius) {
            return 0.0;
        }
        return 1.0 - smoothstep(lakeBlocks / radius);
    }

    static int blendWaterY(int riverY, int lakeY, double t) {
        return (int) Math.round(lerp(riverY, lakeY, clamp01(t)));
    }

    /**
     * Lerp river depth toward lake depth. When the lake is deeper, never shallow
     * below the river mid-blend; when the lake is shallower, follow the lerp.
     */
    static int blendDepth(int riverDepth, int lakeDepth, double t) {
        double amount = clamp01(t);
        int lerped = (int) Math.round(lerp(riverDepth, lakeDepth, amount));
        lerped = Math.max(1, lerped);
        if (lakeDepth >= riverDepth) {
            return Math.max(riverDepth, lerped);
        }
        return lerped;
    }

    /** Optional channel widen near the mouth. */
    static int blendChannelHalfWidth(int riverHalfWidth, int lakeApproachHalfWidth, double t) {
        int blended = (int) Math.round(lerp(riverHalfWidth, lakeApproachHalfWidth, clamp01(t)));
        return Math.max(riverHalfWidth, blended);
    }

    private static double clamp01(double value) {
        return Math.clamp(value, 0.0, 1.0);
    }

    private static double smoothstep(double value) {
        double t = clamp01(value);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double lerp(double start, double end, double amount) {
        return start + (end - start) * amount;
    }
}
