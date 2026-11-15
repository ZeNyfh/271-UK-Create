package com.ukgeo.worldgen;

import java.util.Arrays;

/**
 * Deterministic river water-plane helpers: lowest-bank plane from angular ring samples,
 * smooth bowl depth, and bank-top conformance. No Minecraft types — unit-testable.
 */
final class RiverWaterPlane {
    private static final double BANK_FLUSH_DOUT = 2.5;
    private static final double BANK_RAISE_DOUT = 3.0;
    private static final double BANK_TALUS = 0.55;
    private static final int RING_ANGLES = 16;

    private RiverWaterPlane() {
    }

    /**
     * Robust lowest bank from samples: true min if {@code count < 4}, else ~10th percentile after sort.
     */
    static int robustLowest(int[] samples, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be > 0");
        }
        int[] sorted = Arrays.copyOf(samples, count);
        Arrays.sort(sorted);
        if (count < 4) {
            return sorted[0];
        }
        int idx = (int) Math.floor((count - 1) * 0.10);
        return sorted[Math.clamp(idx, 0, count - 1)];
    }

    /**
     * Shared water-plane Y for every column whose nearest river-mask cell is the same
     * centerline station. Pure function of bank-top samples only — same samples → same plane.
     */
    static int planeFromSamples(int[] bankTops, int count) {
        return robustLowest(bankTops, count);
    }

    /**
     * Bed Y under a fixed station water plane. DEM / provisional bed must never raise
     * {@code waterPlane}; if the bowl would go below {@code minBuildY + 1}, depth is clamped.
     */
    static int bedUnderPlane(int waterPlane, int depth, int minBuildY) {
        return Math.max(minBuildY + 1, waterPlane - Math.max(1, depth));
    }

    /**
     * Stabilize a centerline plane against neighbor stations: take the min so left/right
     * halves of a wide channel that latch onto adjacent mask cells still share one level.
     */
    static int stabilizeWithNeighbors(int plane, int[] neighborPlanes, int count) {
        int result = plane;
        for (int i = 0; i < count; i++) {
            result = Math.min(result, neighborPlanes[i]);
        }
        return result;
    }

    /**
     * Window radius around a query column to gather filled-mask cells for a midline centroid.
     * At least 2 so a thin corridor still yields a stable station.
     */
    static int midlineWindowRadius(int halfWidth) {
        return Math.max(2, Math.max(0, halfWidth));
    }

    /**
     * Snap a coordinate to even for cache stability so nearby columns share one station key.
     */
    static int quantizeEven(int coord) {
        return (coord / 2) * 2;
    }

    /**
     * Weighted integer centroid of mask cells. Weights ≥ 1 (interior 4-neighbour boost).
     * Returns {@code {mx, mz}}; empty input → {@code {0, 0}}.
     */
    static int[] weightedCentroid(int[] xs, int[] zs, int[] weights, int count) {
        if (count <= 0) {
            return new int[] {0, 0};
        }
        long sumX = 0L;
        long sumZ = 0L;
        long sumW = 0L;
        for (int i = 0; i < count; i++) {
            int w = weights == null ? 1 : Math.max(1, weights[i]);
            sumX += (long) xs[i] * w;
            sumZ += (long) zs[i] * w;
            sumW += w;
        }
        if (sumW <= 0L) {
            return new int[] {0, 0};
        }
        int mx = (int) Math.round((double) sumX / (double) sumW);
        int mz = (int) Math.round((double) sumZ / (double) sumW);
        return new int[] {mx, mz};
    }

    /**
     * Candidate bank sample offsets on 1–2 rings around a water column.
     * 16 angles × rings at {@code (waterRadius+2)} and
     * {@code (waterRadius+2 + max(4, waterRadius))} so the opposite bank is reachable
     * even from an edge column. Returns count of (dx,dz) pairs written into outDx/outDz
     * (length ≥ 32).
     */
    static int bankSampleOffsets(int waterRadius, int[] outDx, int[] outDz) {
        int safeRadius = Math.max(0, waterRadius);
        int ring1 = safeRadius + 2;
        int ring2 = safeRadius + 2 + Math.max(4, safeRadius);
        int count = 0;
        count = writeRing(ring1, outDx, outDz, count);
        count = writeRing(ring2, outDx, outDz, count);
        return count;
    }

    private static int writeRing(int ring, int[] outDx, int[] outDz, int count) {
        for (int i = 0; i < RING_ANGLES; i++) {
            double angle = i * (Math.PI * 2.0 / RING_ANGLES);
            outDx[count] = (int) Math.round(Math.cos(angle) * ring);
            outDz[count] = (int) Math.round(Math.sin(angle) * ring);
            count++;
        }
        return count;
    }

    /** Gentle bowl depth without jitter: 1 at edge, deeper at center. */
    static int smoothBowlDepth(double edgeDistance, double halfWidth, int maxDepth) {
        return RiverChannelForm.bowlDepthBlocks(edgeDistance, halfWidth, maxDepth);
    }

    /**
     * Bank top: flush to plane for {@code dOut ≤ 2.5}, else talus {@link RiverChannelForm#bankRise}
     * inland. Low banks near water may rise lightly toward the plane.
     */
    static int conformBankTop(int originalTop, int waterPlane, double dOut, double reach) {
        if (dOut <= 0.0) {
            return originalTop;
        }
        if (originalTop > waterPlane) {
            if (dOut <= BANK_FLUSH_DOUT) {
                return waterPlane;
            }
            double safeReach = Math.max(2.0, reach);
            double heightAbove = originalTop - waterPlane;
            double rise = RiverChannelForm.bankRise(dOut - 1.0, heightAbove, safeReach - 1.0, BANK_TALUS);
            int target = waterPlane + (int) Math.round(Math.min(rise, heightAbove));
            return Math.min(target, originalTop);
        }
        if (originalTop < waterPlane && dOut <= BANK_RAISE_DOUT) {
            return RiverBankFit.raiseBankToward(originalTop, waterPlane);
        }
        return originalTop;
    }
}
