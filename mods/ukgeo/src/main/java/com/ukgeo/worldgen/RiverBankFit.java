package com.ukgeo.worldgen;

/**
 * Pure bank/water-surface fit math for rivers.
 * Keeps water flush with dry bank tops when possible, with a max bank raise of {@link #MAX_BANK_RAISE}.
 * Near-water shelf flush then talus via {@link #bankCarveTarget}; level high→low then conform via
 * {@link #bankConformTarget}.
 */
final class RiverBankFit {
    static final int MAX_BANK_RAISE = 2;
    static final double BANK_CARVE_REACH = 12.0;
    private static final double BANK_CARVE_TALUS = 0.55;

    private RiverBankFit() {
    }

    /**
     * Resolve a water surface against the lowest adjacent dry bank top.
     * <ul>
     *   <li>If water is at bank: stay flush at {@code minBank}.</li>
     *   <li>If water is within {@link #MAX_BANK_RAISE} below bank: raise water to {@code minBank} (flush).</li>
     *   <li>If water is more than {@link #MAX_BANK_RAISE} below bank: leave unchanged (cascade/canyon).</li>
     *   <li>If water exceeds bank by ≤ {@link #MAX_BANK_RAISE}: raise banks (caller), then water ≤ raisedMinBank.</li>
     *   <li>If water exceeds bank by more: lower water to {@code minBank} (do not raise banks &gt; 2).</li>
     * </ul>
     *
     * @param waterSurface current planned water Y
     * @param minBank      minimum adjacent dry terrain top, or {@link Integer#MAX_VALUE} if none
     * @return fitted water surface Y (may equal input)
     */
    static int resolveWaterSurface(int waterSurface, int minBank) {
        if (minBank == Integer.MAX_VALUE) {
            return waterSurface;
        }
        int d = waterSurface - minBank;
        if (d > MAX_BANK_RAISE) {
            return minBank;
        }
        if (d > 0) {
            int raisedMinBank = Math.min(minBank + MAX_BANK_RAISE, waterSurface);
            return Math.min(waterSurface, raisedMinBank);
        }
        // Within MAX_BANK_RAISE below bank: raise water to flush. Deeper stays (cascade/canyon).
        if (d >= -MAX_BANK_RAISE) {
            return minBank;
        }
        return waterSurface;
    }

    /**
     * Raise a dry bank top toward {@code waterSurface} (flush) by at most {@link #MAX_BANK_RAISE}.
     */
    static int raiseBankToward(int bankTop, int waterSurface) {
        int desired = waterSurface;
        if (bankTop >= desired) {
            return bankTop;
        }
        return Math.min(desired, bankTop + MAX_BANK_RAISE);
    }

    /**
     * Preserve channel depth when the water plane moves.
     */
    static int bedForWater(int waterSurface, int depth, int minBuildY) {
        return Math.max(minBuildY + 1, waterSurface - Math.max(1, depth));
    }

    /**
     * Carve target for a dry bank column {@code dOut} blocks from water (near-water shelf flush at {@code waterSurface}).
     * Never returns above {@code originalTop}.
     */
    static int bankCarveTarget(int originalTop, int waterSurface, double dOut, double reach) {
        if (originalTop <= waterSurface) {
            return originalTop;
        }
        if (dOut <= 2.5) {
            return waterSurface;
        }
        double safeReach = Math.max(2.0, reach);
        double heightAbove = originalTop - waterSurface;
        double rise = RiverChannelForm.bankRise(dOut - 1.0, heightAbove, safeReach - 1.0, BANK_CARVE_TALUS);
        int target = waterSurface + (int) Math.round(Math.min(rise, heightAbove));
        return Math.min(target, originalTop);
    }

    /** Target dry-column top for conforming terrain to a bank plane (carve high, light-raise low). */
    static int bankConformTarget(int originalTop, int bankPlane, double dOut, double reach) {
        if (dOut <= 0) return originalTop;
        if (originalTop > bankPlane) {
            return bankCarveTarget(originalTop, bankPlane, dOut, reach);
        }
        if (originalTop < bankPlane && dOut <= 3.0) {
            return raiseBankToward(originalTop, bankPlane);
        }
        return originalTop;
    }
}
