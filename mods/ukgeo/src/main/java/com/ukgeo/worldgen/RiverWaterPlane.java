package com.ukgeo.worldgen;

/**
 * River bed and bank helpers. Water elevation is the baked station Y;
 * these functions never invent a plane from bank samples.
 * Mountain incision is capped via {@link RiverBankFit#cappedPlane}.
 */
final class RiverWaterPlane {
    private static final double BANK_RAISE_DOUT = 3.0;
    private static final double BANK_TALUS = 0.55;

    private RiverWaterPlane() {
    }

    /**
     * Bed Y under a fixed station water plane. DEM / provisional bed must never raise
     * {@code waterPlane}; if the bowl would go below {@code minBuildY + 1}, depth is clamped.
     */
    static int bedUnderPlane(int waterPlane, int depth, int minBuildY) {
        return Math.max(minBuildY + 1, waterPlane - Math.max(1, depth));
    }

    /** Gentle bowl depth without jitter: 1 at edge, deeper at center. */
    static int smoothBowlDepth(double edgeDistance, double halfWidth, int maxDepth) {
        return RiverChannelForm.bowlDepthBlocks(edgeDistance, halfWidth, maxDepth);
    }

    /**
     * Bank top: continuous talus inland from a DEM-capped plane.
     * Low banks near water may rise lightly toward the plane.
     */
    static int conformBankTop(int originalTop, int waterPlane, double dOut, double reach) {
        if (dOut <= 0.0) {
            return originalTop;
        }
        if (originalTop > waterPlane) {
            return RiverBankFit.bankCarveTarget(originalTop, waterPlane, dOut, reach);
        }
        if (originalTop < waterPlane && dOut <= BANK_RAISE_DOUT) {
            return RiverBankFit.raiseBankToward(originalTop, waterPlane);
        }
        return originalTop;
    }
}
