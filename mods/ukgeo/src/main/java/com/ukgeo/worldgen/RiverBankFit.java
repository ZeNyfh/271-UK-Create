package com.ukgeo.worldgen;

/**
 * Pure bank/water-surface fit math for rivers.
 * Keeps banks above water when possible, with a max bank raise of {@link #MAX_BANK_RAISE}.
 */
final class RiverBankFit {
    static final int MAX_BANK_RAISE = 2;

    private RiverBankFit() {
    }

    /**
     * Resolve a water surface against the lowest adjacent dry bank top.
     * <ul>
     *   <li>If water is at/below bank: keep water at most {@code minBank - 1}.</li>
     *   <li>If water exceeds bank by ≤ {@link #MAX_BANK_RAISE}: raise banks (caller), then water ≤ raisedBank - 1.</li>
     *   <li>If water exceeds bank by more: lower water to {@code minBank - 1} (do not raise banks &gt; 2).</li>
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
            return minBank - 1;
        }
        if (d > 0) {
            int raisedMinBank = Math.min(minBank + MAX_BANK_RAISE, waterSurface + 1);
            return Math.min(waterSurface, raisedMinBank - 1);
        }
        // Flush or already below: banks should sit above water.
        return Math.min(waterSurface, minBank - 1);
    }

    /**
     * Raise a dry bank top toward {@code waterSurface + 1} by at most {@link #MAX_BANK_RAISE}.
     */
    static int raiseBankToward(int bankTop, int waterSurface) {
        int desired = waterSurface + 1;
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
}
