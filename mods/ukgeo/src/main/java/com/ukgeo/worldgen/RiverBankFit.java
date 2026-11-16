package com.ukgeo.worldgen;

/**
 * Pure bank/water-surface fit math for rivers.
 * Keeps water flush with dry bank tops when possible, with a max bank raise of {@link #MAX_BANK_RAISE}.
 * Near-water shelf flush then talus via {@link #bankCarveTarget}; level high→low then conform via
 * {@link #bankConformTarget}.
 * Incision below the original DEM is capped so mountain stamps cannot open extreme canyons.
 */
final class RiverBankFit {
    static final int MAX_BANK_RAISE = 2;
    /** Inland bank influence; keep modest to limit triangular stamp facets. */
    static final double BANK_CARVE_REACH = 8.0;
    /** Max blocks an extreme mountain channel may notch below the DEM. */
    static final int MAX_CHANNEL_INCISION = 8;
    /**
     * Only rewrite baked channel water when DEM is this far above it.
     * Normal valley banks (tens of blocks) keep absolute station water Y.
     */
    static final int EXTREME_CHANNEL_DROP = 48;
    /** Max bank drop near water; tapers toward 1 at {@link #BANK_CARVE_REACH}. */
    static final int MAX_BANK_INCISION_NEAR = 6;
    private static final double BANK_CARVE_TALUS = 0.55;

    private RiverBankFit() {
    }

    /**
     * Effective channel water plane. Valley stamps keep baked {@code waterPlane}.
     * Only extreme mountain drops ({@link #EXTREME_CHANNEL_DROP}) are lifted into a shallow DEM notch.
     */
    static int cappedPlane(int originalTop, int waterPlane, int maxIncision) {
        int drop = originalTop - waterPlane;
        // Deep subsurface channels (low DEM, very low water) keep baked Y.
        // Only high mountain columns with valley-floor stamps are notched.
        if (drop <= EXTREME_CHANNEL_DROP || originalTop < 64) {
            return waterPlane;
        }
        return originalTop - Math.max(1, maxIncision);
    }

    /** Allowed drop below original DEM at distance {@code dOut} from the wet edge. */
    static int maxBankIncision(double dOut, double reach) {
        double safeReach = Math.max(1.0, reach);
        double t = Math.clamp(dOut / safeReach, 0.0, 1.0);
        // smoothstep taper: near MAX_BANK_INCISION_NEAR, far ~1
        double s = t * t * (3.0 - 2.0 * t);
        return Math.max(1, (int) Math.round(MAX_BANK_INCISION_NEAR + (1.0 - MAX_BANK_INCISION_NEAR) * s));
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
     * Carve target for a dry bank column {@code dOut} blocks from water.
     * Never returns above {@code originalTop}, and never more than {@link #maxBankIncision} below it.
     */
    static int bankCarveTarget(int originalTop, int waterSurface, double dOut, double reach) {
        if (originalTop <= waterSurface) {
            return originalTop;
        }
        int drop = originalTop - waterSurface;
        boolean extreme = drop > EXTREME_CHANNEL_DROP && originalTop >= 64;
        int plane = extreme
            ? cappedPlane(originalTop, waterSurface, maxBankIncision(dOut, reach))
            : waterSurface;
        if (originalTop <= plane) {
            return originalTop;
        }
        double safeReach = Math.max(2.0, reach);
        double heightAbove = originalTop - plane;
        // Continuous talus from the wet edge (no hard flush shelf → fewer triangular facets).
        double rise = RiverChannelForm.bankRise(Math.max(0.0, dOut), heightAbove, safeReach, BANK_CARVE_TALUS);
        int target = plane + (int) Math.round(Math.min(rise, heightAbove));
        target = Math.min(target, originalTop);
        if (extreme) {
            int floor = originalTop - maxBankIncision(dOut, reach);
            target = Math.max(target, floor);
        }
        return target;
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
