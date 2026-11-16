package com.ukgeo.worldgen;

/**
 * Pure bank/water-surface fit math for rivers.
 * Mountain incision is capped as a total DEM drop including channel bed depth
 * so stamps cannot open artificial canyons. Established lowland valleys keep
 * absolute baked water Y.
 */
final class RiverBankFit {
    static final int MAX_BANK_RAISE = 2;
    /** Inland bank influence; must match Python {@code BANK_CARVE_REACH}. */
    static final double BANK_CARVE_REACH = 8.0;
    /**
     * Max DEM → bed drop for mountain notches (includes channel depth).
     * Target ~2–3 blocks of visible incision into local mountain terrain.
     */
    static final int MOUNTAIN_TOTAL_INCISION = 3;
    /**
     * DEM tops at or above this Y are treated as highland/mountain terrain.
     * Lower established valleys keep absolute baked water even when deep.
     */
    static final int MOUNTAIN_SURFACE_MIN = 128;
    /**
     * When highland DEM is more than this many blocks above baked water, notch
     * shallowly instead of following absolute valley Y.
     */
    static final int MOUNTAIN_DROP_TRIGGER = MOUNTAIN_TOTAL_INCISION;
    /** Near-water bank drop cap on mountain stamps; tapers to 1 at reach. */
    static final int MAX_BANK_INCISION_NEAR = 3;
    private static final double BANK_CARVE_TALUS = 0.55;

    /** Alias for tests / older call sites. */
    static final int MAX_CHANNEL_INCISION = MOUNTAIN_TOTAL_INCISION;
    /** Alias for tests / older call sites. */
    static final int EXTREME_CHANNEL_DROP = MOUNTAIN_DROP_TRIGGER;

    private RiverBankFit() {
    }

    /**
     * Whether this column should notch the DEM instead of following absolute baked water.
     * Highland only — deep lowland valleys stay absolute.
     */
    static boolean isMountainStamp(int originalTop, int waterPlane) {
        return originalTop >= MOUNTAIN_SURFACE_MIN
            && (originalTop - waterPlane) > MOUNTAIN_DROP_TRIGGER;
    }

    /**
     * Channel water plane. Valleys keep baked {@code waterPlane}. Mountain stamps
     * lift the plane so {@code originalTop - bed} ≤ {@link #MOUNTAIN_TOTAL_INCISION}
     * after {@code channelDepth} is applied under the plane.
     */
    static int cappedPlane(int originalTop, int waterPlane, int channelDepth) {
        if (!isMountainStamp(originalTop, waterPlane)) {
            return waterPlane;
        }
        int depth = Math.max(1, channelDepth);
        // original - (plane - depth) <= TOTAL  ⇒  plane >= original + depth - TOTAL
        int minPlane = originalTop + depth - MOUNTAIN_TOTAL_INCISION;
        // Keep at least 1 block of water column under the DEM top.
        minPlane = Math.max(minPlane, originalTop - (MOUNTAIN_TOTAL_INCISION - 1));
        return Math.max(waterPlane, minPlane);
    }

    /** Allowed drop below original DEM at distance {@code dOut} from the wet edge (mountain banks). */
    static int maxBankIncision(double dOut, double reach) {
        double safeReach = Math.max(1.0, reach);
        double t = Math.clamp(dOut / safeReach, 0.0, 1.0);
        double s = t * t * (3.0 - 2.0 * t);
        return Math.max(1, (int) Math.round(MAX_BANK_INCISION_NEAR + (1.0 - MAX_BANK_INCISION_NEAR) * s));
    }

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
        if (d >= -MAX_BANK_RAISE) {
            return minBank;
        }
        return waterSurface;
    }

    static int raiseBankToward(int bankTop, int waterSurface) {
        if (bankTop >= waterSurface) {
            return bankTop;
        }
        return Math.min(waterSurface, bankTop + MAX_BANK_RAISE);
    }

    static int bedForWater(int waterSurface, int depth, int minBuildY) {
        return Math.max(minBuildY + 1, waterSurface - Math.max(1, depth));
    }

    /**
     * Carve target for a dry bank. Mountain stamps cap total DEM drop;
     * valley banks still talus toward the baked plane.
     */
    static int bankCarveTarget(int originalTop, int waterSurface, double dOut, double reach) {
        if (originalTop <= waterSurface) {
            return originalTop;
        }
        boolean mountain = isMountainStamp(originalTop, waterSurface);
        int plane = mountain
            ? cappedPlane(originalTop, waterSurface, 1)
            : waterSurface;
        if (originalTop <= plane) {
            return originalTop;
        }
        double safeReach = Math.max(2.0, reach);
        double heightAbove = originalTop - plane;
        double rise = RiverChannelForm.bankRise(Math.max(0.0, dOut), heightAbove, safeReach, BANK_CARVE_TALUS);
        int target = plane + (int) Math.round(Math.min(rise, heightAbove));
        target = Math.min(target, originalTop);
        if (mountain) {
            int floor = originalTop - maxBankIncision(dOut, reach);
            target = Math.max(target, floor);
        }
        return target;
    }

    static int bankConformTarget(int originalTop, int bankPlane, double dOut, double reach) {
        if (dOut <= 0) {
            return originalTop;
        }
        if (originalTop > bankPlane) {
            return bankCarveTarget(originalTop, bankPlane, dOut, reach);
        }
        if (originalTop < bankPlane && dOut <= 3.0) {
            return raiseBankToward(originalTop, bankPlane);
        }
        return originalTop;
    }
}
