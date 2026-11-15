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
