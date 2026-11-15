package com.ukgeo.worldgen;

/**
 * Pure lateral channel / bank fillet math for river cross-sections.
 * Bowl depths deepen toward the centerline; bank rises follow a talus-style curve
 * (same idea as Dynamic Waters {@code ChannelForm.bankRise} / levee fall — original ukgeo math).
 */
final class RiverChannelForm {
    private RiverChannelForm() {
    }

    /**
     * Bowl factor from normalized lateral offset.
     *
     * @param lateral01 0 at centerline, 1 at channel edge
     * @return depth factor in [0,1] (1 = deepest)
     */
    static double bowlDepth01(double lateral01) {
        double s = Math.clamp(lateral01, 0.0, 1.0);
        return 1.0 - s * s;
    }

    /**
     * Map edge-distance-from-bank (0 at water edge, ~radius at center) to lateral 01.
     *
     * @return 0 at center, 1 at edge
     */
    static double normalizedLateral01(double edgeDistance, double halfWidth) {
        double radius = Math.max(1.5, halfWidth + 0.5);
        double towardCenter = Math.clamp(edgeDistance / radius, 0.0, 1.0);
        return 1.0 - towardCenter;
    }

    /**
     * Rise of dry bank above the channel floor plane as distance grows outward.
     * At {@code dOut <= 0} returns 0; approaches {@code height} near {@code reach}.
     */
    static double bankRise(double dOut, double height, double reach, double talus) {
        if (dOut <= 0.0) {
            return 0.0;
        }
        double safeReach = Math.max(1.0, reach);
        double t = Math.min(1.0, dOut / safeReach);
        double safeTalus = Math.max(0.0, talus);
        double rest = Math.max(0.0, height - safeReach * safeTalus);
        return dOut * safeTalus + rest * Math.pow(t, 2.0 + Math.sqrt(safeTalus));
    }

    /**
     * Effective channel half-width, with a modest floor for steep mountain brooks.
     */
    static int effectiveHalfWidth(int halfWidth, double slope, int order) {
        int result = Math.max(1, halfWidth);
        if (slope >= 2.0 && order <= 2) {
            return Math.max(result, Math.min(2, halfWidth + 1));
        }
        return result;
    }

    /**
     * Integer channel depth from edge distance: 1 at the water edge, deeper toward center.
     */
    static int bowlDepthBlocks(double edgeDistance, double halfWidth, int maxDepth) {
        int capped = Math.max(1, maxDepth);
        double lateral = normalizedLateral01(edgeDistance, halfWidth);
        double factor = bowlDepth01(lateral);
        int depth = 1 + (int) Math.round(factor * (capped - 1));
        return Math.clamp(depth, 1, capped);
    }

    /**
     * Low-frequency ±1 block jitter on channel depth; edges stay shallower than center.
     */
    static int applyDepthJitter(int depth, int maxDepth, int x, int z, double edgeDistance, double halfWidth) {
        double lateral = normalizedLateral01(edgeDistance, halfWidth);
        int jitter = depthJitterDelta(x, z);
        int result = depth + jitter;
        if (lateral > 0.65) {
            result = Math.min(result, depth);
        }
        return Math.clamp(result, 1, Math.max(1, maxDepth));
    }

    /** Soft water-edge occupancy wobble (~±0.5 blocks) to break perfect geometric rings. */
    static double edgeOccupancyJitter(int x, int z) {
        double n = hashUnit(x >> 1, z >> 1, 0x5249464544474A31L);
        return (n - 0.5);
    }

    private static int depthJitterDelta(int x, int z) {
        double n = hashUnit(x >> 2, z >> 2, 0x5249464A49545431L);
        if (n < 0.33) {
            return -1;
        }
        if (n > 0.67) {
            return 1;
        }
        return 0;
    }

    private static double hashUnit(int x, int z, long salt) {
        return (hashNoise(x, z, salt) + 1.0) * 0.5;
    }

    private static double hashNoise(int x, int z, long salt) {
        long value = salt;
        value ^= x * 0x9E3779B97F4A7C15L;
        value ^= z * 0xC2B2AE3D27D4EB4FL;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return ((value >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
    }
}
