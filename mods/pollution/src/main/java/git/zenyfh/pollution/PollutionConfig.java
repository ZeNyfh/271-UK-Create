package git.zenyfh.pollution;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class PollutionConfig {
    public static final int MIN_EFFECTIVE_SOURCE_SCAN_RADIUS_CHUNKS = 16;
    public static final int MIN_EFFECTIVE_SYNC_RADIUS_CHUNKS = 24;
    public static final int MIN_EFFECTIVE_VISUAL_RADIUS_CHUNKS = 20;
    public static final int MIN_EFFECTIVE_GRID_EDGE_FADE_CHUNKS = 4;
    public static final int MIN_EFFECTIVE_CLIENT_CACHE_RADIUS_CHUNKS = 64;
    public static final int MIN_EFFECTIVE_VISUAL_SOURCE_SYNC_RADIUS_CHUNKS = 24;
    public static final int MIN_EFFECTIVE_MAX_SYNCED_VISUAL_SOURCES = 256;
    public static final int MIN_EFFECTIVE_CLIENT_SOURCE_SCAN_RADIUS_CHUNKS = 16;
    public static final int MIN_EFFECTIVE_RERENDER_MARGIN_CHUNKS = 4;
    public static final int MIN_EFFECTIVE_RERENDER_MAX_CHUNKS_PER_TICK = 128;
    public static final double MIN_EFFECTIVE_SOURCE_VISUAL_RADIUS_BLOCKS = 96.0;

    public static final ModConfigSpec SERVER_SPEC;

    public static final ModConfigSpec.BooleanValue POLLUTION_ENABLED;
    public static final ModConfigSpec.IntValue POLLUTION_SIMULATION_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue POLLUTION_SOURCE_SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue POLLUTION_SYNC_INTERVAL_TICKS;
    public static final ModConfigSpec.DoubleValue POLLUTION_SPREAD_RATE;
    public static final ModConfigSpec.DoubleValue POLLUTION_DIAGONAL_SPREAD_WEIGHT;
    public static final ModConfigSpec.DoubleValue POLLUTION_DECAY_RATE;
    public static final ModConfigSpec.DoubleValue POLLUTION_MAX_PER_CHUNK;
    public static final ModConfigSpec.DoubleValue POLLUTION_STORAGE_THRESHOLD;
    public static final ModConfigSpec.IntValue POLLUTION_SOURCE_SCAN_RADIUS_CHUNKS;
    public static final ModConfigSpec.IntValue POLLUTION_SYNC_RADIUS_CHUNKS;
    public static final ModConfigSpec.IntValue POLLUTION_VISUAL_RADIUS_CHUNKS;
    public static final ModConfigSpec.IntValue POLLUTION_GRID_EDGE_FADE_CHUNKS;
    public static final ModConfigSpec.IntValue POLLUTION_CLIENT_CACHE_RADIUS_CHUNKS;
    public static final ModConfigSpec.IntValue POLLUTION_CLIENT_CACHE_MAX_AGE_TICKS;
    public static final ModConfigSpec.BooleanValue POLLUTION_CLIENT_CACHE_DECAY_WHEN_UNSEEN;
    public static final ModConfigSpec.DoubleValue POLLUTION_CLIENT_UNSEEN_DECAY_RATE;
    public static final ModConfigSpec.DoubleValue POLLUTION_CLIENT_CACHE_MIN_VALUE;
    public static final ModConfigSpec.IntValue POLLUTION_VISUAL_SOURCE_SYNC_RADIUS_CHUNKS;
    public static final ModConfigSpec.IntValue POLLUTION_MAX_SYNCED_VISUAL_SOURCES;
    public static final ModConfigSpec.DoubleValue POLLUTION_SOURCE_VISUAL_RADIUS_BLOCKS;
    public static final ModConfigSpec.DoubleValue POLLUTION_SOURCE_VISUAL_MULTIPLIER;
    public static final ModConfigSpec.IntValue POLLUTION_VISUAL_SOURCE_HOLD_TICKS;
    public static final ModConfigSpec.DoubleValue POLLUTION_VISUAL_SOURCE_UNSEEN_DECAY_RATE;
    public static final ModConfigSpec.BooleanValue POLLUTION_CLIENT_ONLY_VISUAL_FALLBACK;
    public static final ModConfigSpec.BooleanValue POLLUTION_CLIENT_FALLBACK_WHEN_SERVER_SYNC_ACTIVE;
    public static final ModConfigSpec.IntValue POLLUTION_SERVER_SYNC_TIMEOUT_TICKS;
    public static final ModConfigSpec.IntValue POLLUTION_CLIENT_SOURCE_SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue POLLUTION_CLIENT_SOURCE_SCAN_RADIUS_CHUNKS;
    public static final ModConfigSpec.DoubleValue POLLUTION_VISUAL_EPSILON;
    public static final ModConfigSpec.DoubleValue POLLUTION_VISUAL_RISE_RATE;
    public static final ModConfigSpec.DoubleValue POLLUTION_VISUAL_FALL_RATE;
    public static final ModConfigSpec.BooleanValue POLLUTION_FOG_ENABLED;
    public static final ModConfigSpec.DoubleValue POLLUTION_FOG_MAX;
    public static final ModConfigSpec.DoubleValue POLLUTION_FOG_MIN_DISTANCE_MULTIPLIER;
    public static final ModConfigSpec.BooleanValue POLLUTION_FOG_COLOR_TINT_ENABLED;
    public static final ModConfigSpec.DoubleValue POLLUTION_FOG_COLOR_TINT_STRENGTH;
    public static final ModConfigSpec.DoubleValue POLLUTION_FOG_ENABLE_THRESHOLD;
    public static final ModConfigSpec.DoubleValue POLLUTION_FOG_DISABLE_THRESHOLD;
    public static final ModConfigSpec.DoubleValue POLLUTION_FOG_RISE_RATE;
    public static final ModConfigSpec.DoubleValue POLLUTION_FOG_FALL_RATE;
    public static final ModConfigSpec.BooleanValue POLLUTION_GRASS_TINT_ENABLED;
    public static final ModConfigSpec.BooleanValue POLLUTION_GRASS_GRADIENT_ENABLED;
    public static final ModConfigSpec.DoubleValue POLLUTION_GRASS_TINT_MAX;
    public static final ModConfigSpec.DoubleValue POLLUTION_GRASS_TINT_STRENGTH;
    public static final ModConfigSpec.DoubleValue POLLUTION_GRASS_NOISE_STRENGTH;
    public static final ModConfigSpec.BooleanValue POLLUTION_PLANT_TINT_ENABLED;
    public static final ModConfigSpec.BooleanValue POLLUTION_FOLIAGE_TINT_ENABLED;
    public static final ModConfigSpec.BooleanValue POLLUTION_RERENDER_ENABLED;
    public static final ModConfigSpec.DoubleValue POLLUTION_RERENDER_THRESHOLD;
    public static final ModConfigSpec.IntValue POLLUTION_RERENDER_MARGIN_CHUNKS;
    public static final ModConfigSpec.IntValue POLLUTION_RERENDER_MAX_CHUNKS_PER_TICK;
    private static boolean warnedRaisedRadiusValues;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("pollution");
        POLLUTION_ENABLED = builder
                .comment("When true, active machines emit chunk-level pollution that spreads, decays, and affects local visuals.")
                .define("pollutionEnabled", true);
        POLLUTION_SIMULATION_INTERVAL_TICKS = builder
                .comment("Ticks between pollution spread and decay simulation steps.")
                .defineInRange("pollutionSimulationIntervalTicks", 100, 20, 20 * 60 * 10);
        POLLUTION_SOURCE_SCAN_INTERVAL_TICKS = builder
                .comment("Ticks between loaded-chunk block entity scans for active pollution sources.")
                .defineInRange("pollutionSourceScanIntervalTicks", 100, 20, 20 * 60 * 10);
        POLLUTION_SYNC_INTERVAL_TICKS = builder
                .comment("Ticks between server-to-client local pollution grid syncs.")
                .defineInRange("pollutionSyncIntervalTicks", 40, 10, 20 * 60);
        POLLUTION_SPREAD_RATE = builder
                .comment("Fraction of each polluted chunk's pollution spread to neighbouring chunks each simulation step.")
                .defineInRange("pollutionSpreadRate", 0.22, 0.0, 0.75);
        POLLUTION_DIAGONAL_SPREAD_WEIGHT = builder
                .comment("Relative spread weight for diagonal neighbours. Cardinal neighbours use weight 1.0.")
                .defineInRange("pollutionDiagonalSpreadWeight", 0.6, 0.0, 1.0);
        POLLUTION_DECAY_RATE = builder
                .comment("Fraction of pollution naturally absorbed from each chunk each simulation step.")
                .defineInRange("pollutionDecayRate", 0.0075, 0.0, 0.5);
        POLLUTION_MAX_PER_CHUNK = builder
                .comment("Maximum pollution stored in one chunk.")
                .defineInRange("pollutionMaxPerChunk", 10_000.0, 1.0, 1_000_000.0);
        POLLUTION_STORAGE_THRESHOLD = builder
                .comment("Pollution entries below this value are removed from saved data.")
                .defineInRange("pollutionStorageThreshold", 0.01, 0.0, 100.0);
        POLLUTION_SOURCE_SCAN_RADIUS_CHUNKS = builder
                .comment("Loaded chunk radius around each player to inspect for active pollution-source block entities.")
                .defineInRange("pollutionSourceScanRadiusChunks", 16, 1, 64);
        POLLUTION_SYNC_RADIUS_CHUNKS = builder
                .comment("Chunk radius of pollution values synced to each player. Radius 24 sends a 49x49 grid.")
                .defineInRange("pollutionSyncRadiusChunks", 24, 1, 64);
        POLLUTION_VISUAL_RADIUS_CHUNKS = builder
                .comment("Client-side chunk distance that samples pollution at full strength before the edge fade begins.")
                .defineInRange("pollutionVisualRadiusChunks", 20, 1, 64);
        POLLUTION_GRID_EDGE_FADE_CHUNKS = builder
                .comment("Extra client-side chunks used to smoothly fade sampled pollution to zero at the edge of the synced grid.")
                .defineInRange("pollutionGridEdgeFadeChunks", 4, 0, 16);
        POLLUTION_CLIENT_CACHE_RADIUS_CHUNKS = builder
                .comment("Maximum client-side distance from the player to retain cached visual pollution chunks.")
                .defineInRange("pollutionClientCacheRadiusChunks", 64, 4, 256);
        POLLUTION_CLIENT_CACHE_MAX_AGE_TICKS = builder
                .comment("Maximum age before far, nearly clean client cache entries can be pruned.")
                .defineInRange("pollutionClientCacheMaxAgeTicks", 2400, 20, 20 * 60 * 30);
        POLLUTION_CLIENT_CACHE_DECAY_WHEN_UNSEEN = builder
                .comment("When true, client visual cache entries not refreshed by server sync slowly decay instead of disappearing.")
                .define("pollutionClientCacheDecayWhenUnseen", true);
        POLLUTION_CLIENT_UNSEEN_DECAY_RATE = builder
                .comment("Per-client-tick decay rate for cached visual pollution entries that are outside the latest server packet.")
                .defineInRange("pollutionClientUnseenDecayRate", 0.0025, 0.0, 0.1);
        POLLUTION_CLIENT_CACHE_MIN_VALUE = builder
                .comment("Client cache entries below this display and target value may be pruned when old and far enough away.")
                .defineInRange("pollutionClientCacheMinValue", 0.25, 0.0, 100.0);
        POLLUTION_VISUAL_SOURCE_SYNC_RADIUS_CHUNKS = builder
                .comment("Chunk radius around each player for syncing active pollution source block positions for source-centred visuals.")
                .defineInRange("pollutionVisualSourceSyncRadiusChunks", 24, 1, 64);
        POLLUTION_MAX_SYNCED_VISUAL_SOURCES = builder
                .comment("Maximum active pollution source positions sent to a client per sync packet.")
                .defineInRange("pollutionMaxSyncedVisualSources", 256, 0, 2048);
        POLLUTION_SOURCE_VISUAL_RADIUS_BLOCKS = builder
                .comment("Block radius for local source-centred visual pollution detail around known active machines.")
                .defineInRange("pollutionSourceVisualRadiusBlocks", 96.0, 1.0, 512.0);
        POLLUTION_SOURCE_VISUAL_MULTIPLIER = builder
                .comment("Visual source contribution multiplier applied to active source emission rate.")
                .defineInRange("pollutionSourceVisualMultiplier", 25.0, 0.0, 10_000.0);
        POLLUTION_VISUAL_SOURCE_HOLD_TICKS = builder
                .comment("Ticks to retain unseen source-centred visual entries before removing them once visually weak.")
                .defineInRange("pollutionVisualSourceHoldTicks", 200, 20, 20 * 60 * 10);
        POLLUTION_VISUAL_SOURCE_UNSEEN_DECAY_RATE = builder
                .comment("Per-client-tick decay rate for source-centred visual entries that were not seen this tick/packet.")
                .defineInRange("pollutionVisualSourceUnseenDecayRate", 0.0025, 0.0, 0.1);
        POLLUTION_CLIENT_ONLY_VISUAL_FALLBACK = builder
                .comment("When true, clients also scan nearby loaded chunks for visible active sources to seed purely visual local pollution.")
                .define("pollutionClientOnlyVisualFallback", true);
        POLLUTION_CLIENT_FALLBACK_WHEN_SERVER_SYNC_ACTIVE = builder
                .comment("When true, client-only source scans still run while recent server pollution sync is active.")
                .define("pollutionClientFallbackWhenServerSyncActive", false);
        POLLUTION_SERVER_SYNC_TIMEOUT_TICKS = builder
                .comment("Ticks without a server pollution packet before client-only fallback treats server sync as inactive.")
                .defineInRange("pollutionServerSyncTimeoutTicks", 200, 20, 20 * 60 * 10);
        POLLUTION_CLIENT_SOURCE_SCAN_INTERVAL_TICKS = builder
                .comment("Ticks between client-only fallback scans for active visible pollution sources.")
                .defineInRange("pollutionClientSourceScanIntervalTicks", 40, 10, 20 * 60);
        POLLUTION_CLIENT_SOURCE_SCAN_RADIUS_CHUNKS = builder
                .comment("Loaded client chunk radius scanned for client-only visual source fallback.")
                .defineInRange("pollutionClientSourceScanRadiusChunks", 16, 1, 64);
        POLLUTION_VISUAL_EPSILON = builder
                .comment("Visual pollution below this value is treated as zero to prevent fog and grass flicker.")
                .defineInRange("pollutionVisualEpsilon", 0.5, 0.0, 100.0);
        POLLUTION_VISUAL_RISE_RATE = builder
                .comment("Client interpolation rate when pollution is rising.")
                .defineInRange("pollutionVisualRiseRate", 0.04, 0.001, 1.0);
        POLLUTION_VISUAL_FALL_RATE = builder
                .comment("Client interpolation rate when pollution is falling.")
                .defineInRange("pollutionVisualFallRate", 0.02, 0.001, 1.0);
        POLLUTION_FOG_ENABLED = builder
                .comment("When true, local pollution reduces fog distance on clients.")
                .define("pollutionFogEnabled", true);
        POLLUTION_FOG_MAX = builder
                .comment("Pollution amount that corresponds to maximum fog strength.")
                .defineInRange("pollutionFogMax", 1000.0, 1.0, 100_000.0);
        POLLUTION_FOG_MIN_DISTANCE_MULTIPLIER = builder
                .comment("Fog far distance multiplier at maximum pollution. Lower values mean denser smog.")
                .defineInRange("pollutionFogMinDistanceMultiplier", 0.35, 0.05, 1.0);
        POLLUTION_FOG_COLOR_TINT_ENABLED = builder
                .comment("When true, high pollution subtly tints fog grey-brown.")
                .define("pollutionFogColorTintEnabled", true);
        POLLUTION_FOG_COLOR_TINT_STRENGTH = builder
                .comment("Maximum fog colour blend strength at maximum pollution.")
                .defineInRange("pollutionFogColorTintStrength", 0.35, 0.0, 1.0);
        POLLUTION_FOG_ENABLE_THRESHOLD = builder
                .comment("Pollution fog only activates after the smoothed fog pollution rises above this value.")
                .defineInRange("pollutionFogEnableThreshold", 1.0, 0.0, 100_000.0);
        POLLUTION_FOG_DISABLE_THRESHOLD = builder
                .comment("Pollution fog remains active until the smoothed fog pollution falls below this value.")
                .defineInRange("pollutionFogDisableThreshold", 0.5, 0.0, 100_000.0);
        POLLUTION_FOG_RISE_RATE = builder
                .comment("Client interpolation rate when fog pollution is rising.")
                .defineInRange("pollutionFogRiseRate", 0.04, 0.001, 1.0);
        POLLUTION_FOG_FALL_RATE = builder
                .comment("Client interpolation rate when fog pollution is falling. Keep lower than rise rate to avoid clean-air snapping.")
                .defineInRange("pollutionFogFallRate", 0.015, 0.001, 1.0);
        POLLUTION_GRASS_TINT_ENABLED = builder
                .comment("When true, local pollution desaturates grass toward dead, dirty colours.")
                .define("pollutionGrassTintEnabled", true);
        POLLUTION_GRASS_GRADIENT_ENABLED = builder
                .comment("When true, grass uses a multi-step pollution colour gradient instead of one dead colour.")
                .define("pollutionGrassGradientEnabled", true);
        POLLUTION_GRASS_TINT_MAX = builder
                .comment("Pollution amount that corresponds to maximum grass tint strength.")
                .defineInRange("pollutionGrassTintMax", 800.0, 1.0, 100_000.0);
        POLLUTION_GRASS_TINT_STRENGTH = builder
                .comment("Maximum fraction used when blending biome grass colour toward pollution gradient colours.")
                .defineInRange("pollutionGrassTintStrength", 0.80, 0.0, 1.0);
        POLLUTION_GRASS_NOISE_STRENGTH = builder
                .comment("Small deterministic colour variation strength applied only where sampled pollution is nonzero.")
                .defineInRange("pollutionGrassNoiseStrength", 0.04, 0.0, 0.25);
        POLLUTION_PLANT_TINT_ENABLED = builder
                .comment("When true, grass-like plants are tinted by pollution.")
                .define("pollutionPlantTintEnabled", true);
        POLLUTION_FOLIAGE_TINT_ENABLED = builder
                .comment("When true, leaves and vines are also tinted by pollution. Disabled by default to keep the effect grass-focused.")
                .define("pollutionFoliageTintEnabled", false);
        POLLUTION_RERENDER_ENABLED = builder
                .comment("When true, client chunk meshes are marked dirty when pollution tint levels change so grass colours update without block updates.")
                .define("pollutionRerenderEnabled", true);
        POLLUTION_RERENDER_THRESHOLD = builder
                .comment("Absolute pollution value change required to queue a client chunk rerender, unless the value crosses a visible tint level.")
                .defineInRange("pollutionRerenderThreshold", 5.0, 0.0, 100_000.0);
        POLLUTION_RERENDER_MARGIN_CHUNKS = builder
                .comment("Extra client chunks marked dirty around changed pollution chunks because bilinear sampling affects neighbouring chunk edges.")
                .defineInRange("pollutionRerenderMarginChunks", 4, 0, 12);
        POLLUTION_RERENDER_MAX_CHUNKS_PER_TICK = builder
                .comment("Maximum client chunks whose meshes may be marked dirty per tick for pollution colour refresh.")
                .defineInRange("pollutionRerenderMaxChunksPerTick", 128, 1, 512);
        builder.pop();
        SERVER_SPEC = builder.build();
    }

    private PollutionConfig() {
    }

    public static int effectiveSourceScanRadiusChunks() {
        return effectiveInt("pollutionSourceScanRadiusChunks", POLLUTION_SOURCE_SCAN_RADIUS_CHUNKS.get(), MIN_EFFECTIVE_SOURCE_SCAN_RADIUS_CHUNKS, 64);
    }

    public static int effectiveVisualRadiusChunks() {
        return effectiveInt("pollutionVisualRadiusChunks", POLLUTION_VISUAL_RADIUS_CHUNKS.get(), MIN_EFFECTIVE_VISUAL_RADIUS_CHUNKS, 64);
    }

    public static int effectiveGridEdgeFadeChunks() {
        return effectiveInt("pollutionGridEdgeFadeChunks", POLLUTION_GRID_EDGE_FADE_CHUNKS.get(), MIN_EFFECTIVE_GRID_EDGE_FADE_CHUNKS, 16);
    }

    public static int effectiveSyncRadiusChunks() {
        int minimum = Math.max(MIN_EFFECTIVE_SYNC_RADIUS_CHUNKS, effectiveVisualRadiusChunks() + effectiveGridEdgeFadeChunks());
        return effectiveInt("pollutionSyncRadiusChunks", POLLUTION_SYNC_RADIUS_CHUNKS.get(), minimum, 64);
    }

    public static int effectiveClientCacheRadiusChunks() {
        return effectiveInt("pollutionClientCacheRadiusChunks", POLLUTION_CLIENT_CACHE_RADIUS_CHUNKS.get(), MIN_EFFECTIVE_CLIENT_CACHE_RADIUS_CHUNKS, 256);
    }

    public static int effectiveVisualSourceSyncRadiusChunks() {
        return effectiveInt("pollutionVisualSourceSyncRadiusChunks", POLLUTION_VISUAL_SOURCE_SYNC_RADIUS_CHUNKS.get(), MIN_EFFECTIVE_VISUAL_SOURCE_SYNC_RADIUS_CHUNKS, 64);
    }

    public static int effectiveMaxSyncedVisualSources() {
        return effectiveInt("pollutionMaxSyncedVisualSources", POLLUTION_MAX_SYNCED_VISUAL_SOURCES.get(), MIN_EFFECTIVE_MAX_SYNCED_VISUAL_SOURCES, 2048);
    }

    public static double effectiveSourceVisualRadiusBlocks() {
        return effectiveDouble("pollutionSourceVisualRadiusBlocks", POLLUTION_SOURCE_VISUAL_RADIUS_BLOCKS.get(), MIN_EFFECTIVE_SOURCE_VISUAL_RADIUS_BLOCKS, 512.0);
    }

    public static int effectiveClientSourceScanRadiusChunks() {
        return effectiveInt("pollutionClientSourceScanRadiusChunks", POLLUTION_CLIENT_SOURCE_SCAN_RADIUS_CHUNKS.get(), MIN_EFFECTIVE_CLIENT_SOURCE_SCAN_RADIUS_CHUNKS, 64);
    }

    public static int effectiveRerenderMarginChunks() {
        return effectiveInt("pollutionRerenderMarginChunks", POLLUTION_RERENDER_MARGIN_CHUNKS.get(), MIN_EFFECTIVE_RERENDER_MARGIN_CHUNKS, 12);
    }

    public static int effectiveRerenderMaxChunksPerTick() {
        return effectiveInt("pollutionRerenderMaxChunksPerTick", POLLUTION_RERENDER_MAX_CHUNKS_PER_TICK.get(), MIN_EFFECTIVE_RERENDER_MAX_CHUNKS_PER_TICK, 512);
    }

    private static int effectiveInt(String key, int configured, int minimum, int maximum) {
        int effective = Math.max(minimum, Math.min(configured, maximum));
        warnRaisedLowRadiusValue(key, configured, effective);
        return effective;
    }

    private static double effectiveDouble(String key, double configured, double minimum, double maximum) {
        double effective = Math.max(minimum, Math.min(configured, maximum));
        warnRaisedLowRadiusValue(key, configured, effective);
        return effective;
    }

    private static void warnRaisedLowRadiusValue(String key, double configured, double effective) {
        if (configured >= effective || warnedRaisedRadiusValues) {
            return;
        }
        warnedRaisedRadiusValues = true;
        Pollution.LOGGER.warn(
                "Some pollution radius settings are below the new effective minimums; raising stale config values internally. First raised setting: {} configured={} effective={}",
                key,
                configured,
                effective
        );
    }
}
