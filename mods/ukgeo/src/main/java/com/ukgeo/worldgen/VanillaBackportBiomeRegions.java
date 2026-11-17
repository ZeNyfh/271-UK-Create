package com.ukgeo.worldgen;

/**
 * Pure BNG-space classifiers for VanillaBackport biomes (pale_garden, dappled_forest, sulfur_caves).
 * Boxes are intentionally oversized vs real woodland cover so runtime forest expand can fill gaps.
 *
 * Conflict: pale checked before dappled. Overlap in W Highlands Celtic rainforest → pale wins
 * (western broadleaf-leaning). Highlands/Pennines/Lakes/NI stay dappled outside pale boxes.
 */
public final class VanillaBackportBiomeRegions {
    public enum RegionKind {
        NONE,
        PALE_GARDEN,
        DAPPLED_FOREST
    }

    // --- Pale garden (old woodlands / Celtic oak) — (eMin, nMin, eMax, nMax) ---
    // Dartmoor
    private static final int[] PALE_DARTMOOR = {250_000, 45_000, 300_000, 95_000};
    // Exmoor
    private static final int[] PALE_EXMOOR = {270_000, 120_000, 320_000, 165_000};
    // Eryri / Snowdonia lower slopes
    private static final int[] PALE_ERYRI = {245_000, 320_000, 290_000, 380_000};
    // W Highlands Celtic rainforest
    private static final int[] PALE_W_HIGHLANDS = {100_000, 680_000, 230_000, 880_000};
    // W Ireland oakwood belt
    private static final int[] PALE_W_IRELAND = {-200_000, 180_000, 50_000, 420_000};

    // --- Dappled forest (cold autumn) ---
    // Scottish Highlands + islands
    private static final int[] DAPPLED_HIGHLANDS = {50_000, 700_000, 360_000, 1_050_000};
    // Pennines
    private static final int[] DAPPLED_PENNINES = {340_000, 370_000, 430_000, 560_000};
    // Lake District
    private static final int[] DAPPLED_LAKES = {300_000, 470_000, 365_000, 560_000};
    // NI uplands (Mournes / Sperrins / Antrim)
    private static final int[] DAPPLED_NI = {90_000, 330_000, 200_000, 460_000};

    // --- Sulfur caves (Wales) ---
    // Soft Wales filter
    private static final int[] SULFUR_WALES = {160_000, 160_000, 360_000, 400_000};
    // Vale of Glamorgan / S Wales Triassic sulfate core (higher chance)
    private static final int[] SULFUR_VALE_CORE = {280_000, 160_000, 330_000, 210_000};

    // Extra Ireland densest belts for forest expand only (outside pale/dappled classify boxes).
    // NI densest already covered by DAPPLED_NI; Galway/Mayo by PALE_W_IRELAND.
    // SW Kerry / Cork (south of pale oak belt)
    private static final int[] EXPAND_SW_IRELAND = {-80_000, 40_000, 60_000, 160_000};
    // Wicklow (east of pale oak belt; CRS matches NI/Ireland extents used above)
    private static final int[] EXPAND_WICKLOW = {50_000, 140_000, 120_000, 240_000};

    private static final long EXPAND_SALT = 0x56414E494C4C415FL;

    private VanillaBackportBiomeRegions() {
    }

    public static RegionKind classify(double easting, double northing) {
        if (inAny(easting, northing,
            PALE_DARTMOOR, PALE_EXMOOR, PALE_ERYRI, PALE_W_HIGHLANDS, PALE_W_IRELAND)) {
            return RegionKind.PALE_GARDEN;
        }
        if (inAny(easting, northing,
            DAPPLED_HIGHLANDS, DAPPLED_PENNINES, DAPPLED_LAKES, DAPPLED_NI)) {
            return RegionKind.DAPPLED_FOREST;
        }
        return RegionKind.NONE;
    }

    /** Soft Wales AABB; callers combine with noise for placement. */
    public static boolean inSulfurWales(double easting, double northing) {
        return inBox(easting, northing, SULFUR_WALES);
    }

    public static boolean inSulfurValeCore(double easting, double northing) {
        return inBox(easting, northing, SULFUR_VALE_CORE);
    }

    /** Default cave-noise threshold for Vale sulfate core (lower = more sulfur). */
    public static final double SULFUR_NOISE_THRESHOLD_VALE_CORE = 0.20;
    /** Default cave-noise threshold for soft Wales AABB outside Vale core. */
    public static final double SULFUR_NOISE_THRESHOLD_SOFT_WALES = 0.40;

    public static double defaultSulfurNoiseThreshold(double easting, double northing) {
        return inSulfurValeCore(easting, northing)
            ? SULFUR_NOISE_THRESHOLD_VALE_CORE
            : SULFUR_NOISE_THRESHOLD_SOFT_WALES;
    }

    /**
     * Runtime-only forest expand: promote vegetation classes 3–9 toward woodland in priority
     * AABBs (pale ∪ dappled ∪ densest IE/NI belts). Leaves 0/10/11 alone; rocky (12) never.
     * Deterministic for the same (e,n,class).
     */
    public static boolean shouldExpandForest(double easting, double northing, int vegetationClass, long deterministicNoiseOrHash) {
        if (vegetationClass < 3 || vegetationClass > 9) {
            return false;
        }
        if (!inForestExpandPriority(easting, northing)) {
            return false;
        }
        // ~55% of eligible cells → woodland (oversized vs real CLC cover).
        double unit = unitHash(easting, northing, vegetationClass, deterministicNoiseOrHash);
        return unit < 0.55;
    }

    public static boolean inForestExpandPriority(double easting, double northing) {
        return classify(easting, northing) != RegionKind.NONE
            || inAny(easting, northing, EXPAND_SW_IRELAND, EXPAND_WICKLOW);
    }

    /**
     * After expand decision: which woodland class to treat as.
     * Dappled-prefer regions → conifer (2); otherwise broadleaf (1).
     */
    public static int expandedWoodlandClass(double easting, double northing) {
        return classify(easting, northing) == RegionKind.DAPPLED_FOREST ? 2 : 1;
    }

    private static boolean inAny(double e, double n, int[]... boxes) {
        for (int[] box : boxes) {
            if (inBox(e, n, box)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inBox(double e, double n, int[] box) {
        return e >= box[0] && n >= box[1] && e <= box[2] && n <= box[3];
    }

    private static double unitHash(double easting, double northing, int vegetationClass, long salt) {
        long x = Double.doubleToLongBits(Math.floor(easting));
        long z = Double.doubleToLongBits(Math.floor(northing));
        long value = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ vegetationClass * 0x27D4EB2F165667C5L ^ salt ^ EXPAND_SALT;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-53;
    }
}
