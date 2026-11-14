package com.ukgeo.worldgen;

/**
 * Pure bank/water-surface fit math for rivers.
 * Keeps water flush with dry bank tops when possible, with a max bank raise of {@link #MAX_BANK_RAISE}.
 */
final class RiverBankFit {
    static final int MAX_BANK_RAISE = 2;

    private RiverBankFit() {
    }

    /**
     * Resolve a water surface against the lowest adjacent dry bank top.
     * <ul>
     *   <li>If water is at/below bank: keep water at most {@code minBank} (flush).</li>
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
        // Flush or already below: water at most minBank.
        return Math.min(waterSurface, minBank);
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
}
