package com.ukgeo.worldgen;

/**
 * Continuous deterministic lake-basin depth from edge distance.
 * Evaluated in world coordinates so adjacent chunks agree without
 * chunk-local averaging. No high-frequency occupancy/depth jitter.
 */
final class LakeBathymetry {
    /** Soft shelf before the basin deepens. */
    static final double SHELF_BLOCKS = 3.0;
    /** Distance past the shelf where the basin approaches full depth. */
    static final double BASIN_BLOCKS = 14.0;
    static final int MAX_DEPTH = 12;

    private LakeBathymetry() {
    }

    /**
     * Depth under the lake water surface for a column {@code edgeDistance}
     * blocks inside the freshwater mask (0 at the shore).
     */
    static int depthBlocks(double edgeDistance, int worldX, int worldZ) {
        double d = Math.max(0.0, edgeDistance);
        if (d <= 0.75) {
            return 1;
        }
        int maxDepth = maxDepthForInterior(d);
        // Broad shelf then smooth basin — avoids concentric square rings.
        double shelf = smoothstep((d - 0.5) / SHELF_BLOCKS);
        double basin = smoothstep((d - SHELF_BLOCKS) / BASIN_BLOCKS);
        double factor = 0.18 * shelf + 0.82 * basin;
        // Very low-frequency ±1 variation so lakes are not identical bowls.
        int depth = 1 + (int) Math.round(factor * (maxDepth - 1));
        depth += lowFrequencyDelta(worldX, worldZ);
        return Math.clamp(depth, 1, maxDepth);
    }

    static int maxDepthForInterior(double edgeDistance) {
        if (edgeDistance <= 2.0) {
            return 2;
        }
        if (edgeDistance <= 4.0) {
            return 4;
        }
        if (edgeDistance <= 7.0) {
            return 6;
        }
        if (edgeDistance <= 11.0) {
            return 8;
        }
        if (edgeDistance <= 16.0) {
            return 10;
        }
        return MAX_DEPTH;
    }

    /** Coarse value-noise delta in {-1,0,+1}; spatially coherent. */
    static int lowFrequencyDelta(int worldX, int worldZ) {
        double n = SurfaceExposureNoise.valueNoise2(worldX * 0.035, worldZ * 0.035, 0x4C414B45);
        if (n < 0.28) {
            return -1;
        }
        if (n > 0.78) {
            return 1;
        }
        return 0;
    }

    private static double smoothstep(double value) {
        double t = Math.clamp(value, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }
}
