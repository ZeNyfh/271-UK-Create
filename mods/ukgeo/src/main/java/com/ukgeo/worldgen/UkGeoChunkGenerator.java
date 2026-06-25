package com.ukgeo.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.placement.VegetationPlacements;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.material.Fluids;

public final class UkGeoChunkGenerator extends ChunkGenerator {
    private static final int OIL_SCORE_THRESHOLD = 64;
    private static final int OIL_DEPOSIT_MIN_MILLIBUCKETS = 4_250_000;
    private static final int OIL_DEPOSIT_MAX_MILLIBUCKETS = 9_500_000;
    private static final int[] OIL_SAMPLE_OFFSETS = {4, 8, 12};
    private static final ResourceLocation FARMERS_DELIGHT_RICE_ID = ResourceLocation.parse("farmersdelight:rice");
    private static final boolean DEBUG_WATER_PLAN = Boolean.getBoolean("ukgeo.debugWaterPlan");
    private static final boolean DEBUG_GEN_TIMINGS = Boolean.getBoolean("ukgeo.debugGenTimings");
    private static final boolean CLEAN_PLANNED_DELEGATE_FLUIDS = Boolean.getBoolean("ukgeo.cleanPlannedDelegateFluids");
    private static final boolean SCHEDULE_FULL_WATER_COLUMNS = Boolean.getBoolean("ukgeo.scheduleFullWaterColumns");
    private static final boolean ENABLE_CREATE_DIESEL_OIL_INTEGRATION = !Boolean.getBoolean("ukgeo.disableCreateDieselOilIntegration");
    private static final boolean DEBUG_OIL_GEN = Boolean.getBoolean("ukgeo.debugOilGen");
    private static final boolean DEBUG_CAVES = Boolean.getBoolean("ukgeo.debugCaves");
    private static final boolean DEBUG_ORE_HEIGHTS = Boolean.getBoolean("ukgeo.debugOreHeights");
    private static final boolean PRESERVE_DELEGATE_NOISE_CAVES = Boolean.getBoolean("ukgeo.preserveDelegateNoiseCaves");
    private static final boolean ENABLE_VANILLA_CARVERS = !Boolean.getBoolean("ukgeo.disableVanillaCarvers");
    private static final boolean ENABLE_DEEP_CARVERS = !Boolean.getBoolean("ukgeo.disableDeepCaves");
    private static final boolean ENABLE_BIOME_FEATURE_DECORATION = !Boolean.getBoolean("ukgeo.disableBiomeFeatureDecoration");
    private static final boolean DEBUG_BIOME_DECORATION = Boolean.getBoolean("ukgeo.debugBiomeDecoration") || DEBUG_GEN_TIMINGS;
    private static final boolean DEBUG_STRUCTURE_CLEANUP = Boolean.getBoolean("ukgeo.debugStructureCleanup") || DEBUG_GEN_TIMINGS;
    private static final long SLOW_FILL_FROM_NOISE_WARN_MS = Long.getLong("ukgeo.slowFillFromNoiseWarnMs", 500L);
    private static final long SLOW_APPLY_BIOME_DECORATION_WARN_MS = Long.getLong("ukgeo.slowApplyBiomeDecorationWarnMs", 500L);
    private static final long SLOW_BIOME_DECORATION_WARN_MS = Long.getLong("ukgeo.slowBiomeDecorationWarnMs", 1_000L);
    private static final long FULL_BIOME_DECORATION_AUTO_DISABLE_MS = Long.getLong("ukgeo.fullBiomeDecorationAutoDisableMs", 3_000L);
    private static final boolean ENABLE_SAFE_MODDED_PLANTS = !Boolean.getBoolean("ukgeo.disableSafeModdedPlants");
    private static final boolean ENABLE_ANCIENT_CITY_AIR_CLEANUP = !Boolean.getBoolean("ukgeo.disableAncientCityAirCleanup");
    private static final int ANCIENT_CITY_AIR_CLEANUP_MIN_Y = Integer.getInteger("ukgeo.ancientCityAirCleanupMinY", -80);
    private static final int ANCIENT_CITY_AIR_CLEANUP_MAX_Y = Integer.getInteger("ukgeo.ancientCityAirCleanupMaxY", 96);
    private static final int MAX_PENDING_CHUNK_PLANS = Integer.getInteger("ukgeo.maxPendingChunkPlans", 4096);
    private static final int MAX_BASE_COLUMN_CACHE = Integer.getInteger("ukgeo.maxBaseColumnCache", 8192);
    private static final int MAX_TREE_FEATURE_CACHE = Integer.getInteger("ukgeo.maxTreeFeatureCache", 4096);
    private static final int PERF_LOG_INTERVAL_CHUNKS = Integer.getInteger("ukgeo.perfLogIntervalChunks", 200);
    private static final int DEBUG_WATER_X = Integer.getInteger("ukgeo.debugWaterX", 30);
    private static final int DEBUG_WATER_Z = Integer.getInteger("ukgeo.debugWaterZ", 72);
    private static final int DEBUG_WATER_Y = Integer.getInteger("ukgeo.debugWaterY", 67);
    private static final int DEBUG_WATER_RADIUS = Integer.getInteger("ukgeo.debugWaterRadius", 2);
    private static final int VEGETATION_BROADLEAF_WOODLAND = 1;
    private static final int VEGETATION_CONIFER_WOODLAND = 2;
    private static final int VEGETATION_ARABLE = 3;
    private static final int VEGETATION_IMPROVED_GRASSLAND = 4;
    private static final int VEGETATION_NEUTRAL_GRASSLAND = 5;
    private static final int VEGETATION_CALCAREOUS_GRASSLAND = 6;
    private static final int VEGETATION_ACID_GRASSLAND = 7;
    private static final int VEGETATION_WETLAND = 8;
    private static final int VEGETATION_HEATH = 9;
    static final int VEGETATION_FRESHWATER = 10;
    private static final int VEGETATION_URBAN = 11;
    private static final int VEGETATION_ROCKY = 12;
    private static final int LAKE_EDGE_SEARCH_RADIUS = 28;
    private static final int LAKE_DEPTH_SMOOTHING_RADIUS = 2;
    private static final int LAKE_BANK_BLEND_RADIUS = 3;
    private static final int LAKE_ARTIFACT_SCAN_RADIUS = 24;
    private static final int LAKE_ARTIFACT_MAX_COMPONENT = 768;
    private static final double LAKE_ARTIFACT_CORRIDOR_OVERLAP = 0.70;
    private static final int WATERBED_PROTECTION_DEPTH = 6;
    private static final int MAX_RIVER_SURFACE_STEP = 1;
    private static final int RIVER_REACH_SMOOTHING_RADIUS = 6;
    private static final double ALLOW_STEEP_RAPIDS_ONLY_ABOVE_SLOPE = 1.8;
    private static final int WATER_EDGE_SMOOTHING_RADIUS = 16;
    private static final int WATER_EDGE_SAMPLE_STEP = 4;
    private static final int WATER_EDGE_MAX_LAND_HEIGHT_ABOVE_SEA = 24;
    private static final int SHALLOW_WATER_DEPTH = 2;
    private static final double BACKGROUND_ORE_ATTEMPT_MULTIPLIER = 0.1;
    private static final double ORE_AREA_ATTEMPT_MULTIPLIER = 3.0;
    private static final int SNOW_ICE_MIN_Y = 501;
    private static final int VANILLA_MIN_Y = -64;
    private static final int VANILLA_MAX_Y = 320;
    private static final int DEFAULT_EFFECTIVE_ORE_TERRAIN_MAX_Y = Integer.getInteger("ukgeo.effectiveOreTerrainMaxY", 280);
    private static final int DEEP_CAVE_MIN_Y = -120;
    private static final int DEEP_CAVE_MAX_Y = -65;
    private static final int DEEP_CAVE_BOTTOM_MARGIN = 8;
    private static final int DEEP_CAVE_ORIGIN_CHUNK_RADIUS = 2;
    private static final int MAX_DEBUG_CARVER_BIOME_LOGS = 16;
    private static final double FLORA_DENSITY_MULTIPLIER = 0.72D;
    private static final double FLORA_CLUSTER_THRESHOLD = 0.40D;
    private static final double FLORA_CLUSTER_FILL_MULTIPLIER = 0.86D;
    private static final double FLOWER_CHANCE_MULTIPLIER = 1.05D;
    private static final double TALL_FLORA_CHANCE_MULTIPLIER = 0.92D;
    private static final double FERN_CHANCE_MULTIPLIER = 0.82D;
    private static final double AMBIENT_FLORA_CHANCE = 0.050D;
    private static final double AMBIENT_TALL_GRASS_CHANCE = 0.22D;
    private static final double AMBIENT_FLOWER_CHANCE = 0.11D;
    private static final double AMBIENT_FERN_CHANCE = 0.10D;
    private static final boolean DEBUG_FLORA_TIMINGS = Boolean.getBoolean("ukgeo.debugFloraTimings");
    private static final boolean DEBUG_ORE_PLACEMENT = Boolean.getBoolean("ukgeo.debugOrePlacement");
    private static final boolean DEBUG_HEIGHT_BOUNDS = Boolean.getBoolean("ukgeo.debugHeightBounds");
    private static final int MAX_DEBUG_HEIGHT_BOUNDS_LOGS = 32;
    private static volatile boolean caveModeLogged;
    private static final AtomicInteger debugHeightBoundsLogs = new AtomicInteger();
    private static final Set<String> debuggedOreHeights = ConcurrentHashMap.newKeySet();
    private static final Set<String> debuggedCarverBiomes = ConcurrentHashMap.newKeySet();
    private static final PerfCounters PERF = new PerfCounters();
    private static final String[] MODDED_ARABLE_PLANTS = {
        "farmersdelight:wild_cabbages",
        "farmersdelight:wild_onions",
        "farmersdelight:wild_tomatoes",
        "farmersdelight:wild_carrots",
        "farmersdelight:wild_potatoes",
        "farmersdelight:wild_beetroots"
    };
    private static final String[] MODDED_GRASSLAND_PLANTS = {
        "farmersdelight:wild_cabbages",
        "farmersdelight:wild_onions",
        "farmersdelight:wild_carrots",
        "farmersdelight:wild_potatoes",
        "wildernature:bluebell",
        "wildernature:lavender",
        "wildernature:thistle"
    };
    private static final String[] MODDED_HEATH_PLANTS = {
        "wildernature:heather",
        "wildernature:lavender",
        "wildernature:thistle",
        "wildernature:bluebell"
    };
    private static final String[] MODDED_WETLAND_PLANTS = {
        "wildernature:cattail",
        "wildernature:reed",
        "wildernature:sedge"
    };
    private static final String[] MODDED_BROADLEAF_PLANTS = {
        "farmersdelight:brown_mushroom_colony",
        "farmersdelight:red_mushroom_colony",
        "wildernature:bluebell",
        "wildernature:small_fern",
        "wildernature:fern"
    };
    private static final String[] MODDED_CONIFER_PLANTS = {
        "farmersdelight:brown_mushroom_colony",
        "farmersdelight:red_mushroom_colony",
        "wildernature:small_fern",
        "wildernature:fern"
    };
    private static final List<ResourceKey<PlacedFeature>> VANILLA_TREE_FEATURES = List.of(
        VegetationPlacements.TREES_PLAINS,
        VegetationPlacements.TREES_BIRCH_AND_OAK,
        VegetationPlacements.TREES_TAIGA,
        VegetationPlacements.TREES_MEADOW,
        VegetationPlacements.TREES_FLOWER_FOREST,
        VegetationPlacements.TREES_WINDSWEPT_HILLS,
        VegetationPlacements.TREES_SWAMP,
        VegetationPlacements.TREES_SPARSE_JUNGLE,
        VegetationPlacements.TREES_WINDSWEPT_SAVANNA
    );

    public static final MapCodec<UkGeoChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
        BiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.biomeSource),
        NoiseGeneratorSettings.CODEC.optionalFieldOf("cave_settings").forGetter(generator -> generator.caveSettings),
        Codec.INT.optionalFieldOf("sea_level_y", 64).forGetter(generator -> generator.seaLevelY),
        Codec.DOUBLE.optionalFieldOf("height_scale", 1.0).forGetter(generator -> generator.heightScale),
        Codec.DOUBLE.optionalFieldOf("lowland_extra_scale", 0.0).forGetter(generator -> generator.lowlandExtraScale),
        Codec.DOUBLE.optionalFieldOf("lowland_ceiling_metres", 120.0).forGetter(generator -> generator.lowlandCeilingMetres),
        Codec.DOUBLE.optionalFieldOf("highland_scale", 1.0).forGetter(generator -> generator.highlandScale),
        Codec.DOUBLE.optionalFieldOf("highland_start_metres", 300.0).forGetter(generator -> generator.highlandStartMetres),
        Codec.DOUBLE.optionalFieldOf("highland_full_metres", 900.0).forGetter(generator -> generator.highlandFullMetres),
        Codec.INT.optionalFieldOf("highland_smoothing_radius", 0).forGetter(generator -> generator.highlandSmoothingRadius),
        Codec.INT.optionalFieldOf("nodata_surface_y", 52).forGetter(generator -> generator.nodataSurfaceY),
        Codec.INT.optionalFieldOf("river_widen_radius", 0).forGetter(generator -> generator.riverWidenRadius),
        Codec.INT.optionalFieldOf("river_carve_depth", 2).forGetter(generator -> generator.riverCarveDepth),
        Codec.INT.optionalFieldOf("min_y", -128).forGetter(generator -> generator.minY),
        Codec.INT.optionalFieldOf("gen_depth", 512).forGetter(generator -> generator.genDepth),
        Codec.INT.optionalFieldOf("fallback_height", 72).forGetter(generator -> generator.fallbackHeight)
    ).apply(instance, UkGeoChunkGenerator::new));

    private final int seaLevelY;
    private final double heightScale;
    private final double lowlandExtraScale;
    private final double lowlandCeilingMetres;
    private final double highlandScale;
    private final double highlandStartMetres;
    private final double highlandFullMetres;
    private final int highlandSmoothingRadius;
    private final int nodataSurfaceY;
    private final int riverWidenRadius;
    private final int riverCarveDepth;
    private final int minY;
    private final int genDepth;
    private final int fallbackHeight;
    private final boolean useConfigDataRoot;
    private final Optional<Holder<NoiseGeneratorSettings>> caveSettings;
    private final Optional<NoiseBasedChunkGenerator> caveDelegate;
    private volatile RuntimeData runtimeData;
    private volatile boolean attemptedDataLoad;
    private final Map<String, Optional<BlockStatePair>> blockStateCache = new ConcurrentHashMap<>();
    private final Map<Integer, BlockState> surfaceBlockCache = new ConcurrentHashMap<>();
    private final Map<String, OptionalBlock> optionalPlantCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ChunkTerrainPlanner.Plan> chunkPlans = new ConcurrentHashMap<>();
    private static final AtomicBoolean DECORATION_CONFIG_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean FULL_BIOME_DECORATION_RUNTIME_DISABLED = new AtomicBoolean();
    private static final AtomicInteger ACTIVE_FULL_BIOME_DECORATIONS = new AtomicInteger();
    private final ConcurrentHashMap<Long, ChunkTerrainPlanner.Plan> decorationWaterPlans = new ConcurrentHashMap<>();
    private final BoundedCache<BaseQueryKey, BaseColumnPlan> baseColumnCache = new BoundedCache<>(MAX_BASE_COLUMN_CACHE);
    private final BoundedCache<Long, Set<ResourceKey<PlacedFeature>>> chunkTreeFeatureCache = new BoundedCache<>(MAX_TREE_FEATURE_CACHE);

    public UkGeoChunkGenerator(
        BiomeSource biomeSource,
        Optional<Holder<NoiseGeneratorSettings>> caveSettings,
        int seaLevelY,
        double heightScale,
        double lowlandExtraScale,
        double lowlandCeilingMetres,
        double highlandScale,
        double highlandStartMetres,
        double highlandFullMetres,
        int highlandSmoothingRadius,
        int nodataSurfaceY,
        int riverWidenRadius,
        int riverCarveDepth,
        int minY,
        int genDepth,
        int fallbackHeight
    ) {
        super(biomeSource, UkGeoChunkGenerator::sanitizeBiomeSettings);
        this.caveSettings = caveSettings;
        this.caveDelegate = caveSettings.map(settings -> new NoiseBasedChunkGenerator(biomeSource, settings));
        this.seaLevelY = seaLevelY;
        this.heightScale = heightScale;
        this.lowlandExtraScale = Math.max(0.0, lowlandExtraScale);
        this.lowlandCeilingMetres = Math.max(1.0, lowlandCeilingMetres);
        this.highlandScale = highlandScale;
        this.highlandStartMetres = highlandStartMetres;
        this.highlandFullMetres = Math.max(highlandStartMetres + 1.0, highlandFullMetres);
        this.highlandSmoothingRadius = Math.max(0, highlandSmoothingRadius);
        this.nodataSurfaceY = nodataSurfaceY;
        this.riverWidenRadius = Math.max(0, riverWidenRadius);
        this.riverCarveDepth = Math.max(1, riverCarveDepth);
        this.minY = minY;
        this.genDepth = genDepth;
        this.fallbackHeight = fallbackHeight;
        this.useConfigDataRoot = true;
    }

    private static BiomeGenerationSettings sanitizeBiomeSettings(Holder<Biome> biome) {
        Map<GenerationStep.Carving, HolderSet<ConfiguredWorldCarver<?>>> carvers = new HashMap<>();
        BiomeGenerationSettings original = biome.value().getGenerationSettings();
        for (GenerationStep.Carving stage : original.getCarvingStages()) {
            List<Holder<ConfiguredWorldCarver<?>>> stageCarvers = new ArrayList<>();
            for (Holder<ConfiguredWorldCarver<?>> carver : original.getCarvers(stage)) {
                stageCarvers.add(carver);
            }
            carvers.put(stage, HolderSet.direct(stageCarvers));
        }
        if (DEBUG_CAVES) {
            logBiomeCarvers(biome, original);
        }
        List<HolderSet<PlacedFeature>> sanitized = new ArrayList<>();
        for (HolderSet<PlacedFeature> step : original.features()) {
            List<Holder<PlacedFeature>> kept = new ArrayList<>();
            for (Holder<PlacedFeature> feature : step) {
                if (!isExcludedBiomeFeature(feature)) {
                    kept.add(feature);
                }
            }
            sanitized.add(HolderSet.direct(kept));
        }
        int oreStep = GenerationStep.Decoration.UNDERGROUND_ORES.ordinal();
        if (sanitized.size() > oreStep) {
            sanitized.set(oreStep, HolderSet.direct(List.<Holder<PlacedFeature>>of()));
        }
        return new BiomeGenerationSettings(carvers, List.copyOf(sanitized));
    }

    private static void logBiomeCarvers(Holder<Biome> biome, BiomeGenerationSettings settings) {
        String biomeId = biome.unwrapKey()
            .map(key -> key.location().toString())
            .orElse("<unregistered>");
        if (debuggedCarverBiomes.size() >= MAX_DEBUG_CARVER_BIOME_LOGS || !debuggedCarverBiomes.add(biomeId)) {
            return;
        }
        int total = 0;
        StringBuilder stages = new StringBuilder();
        for (GenerationStep.Carving stage : settings.getCarvingStages()) {
            int count = 0;
            for (Holder<ConfiguredWorldCarver<?>> ignored : settings.getCarvers(stage)) {
                count++;
            }
            total += count;
            if (!stages.isEmpty()) {
                stages.append(", ");
            }
            stages.append(stage.getName()).append('=').append(count);
        }
        UkGeoMod.LOGGER.info("UKGeo cave debug biome={} totalCarvers={} stages=[{}]", biomeId, total, stages);
    }

    private static void logCaveMode(ChunkTerrainPlanner.CaveMask caveMask, int worldMinY, int maxY, int seaLevel) {
        if (!caveModeLogged) {
            caveModeLogged = true;
            if (caveMask.usesDelegate()) {
                UkGeoMod.LOGGER.warn(
                    "Preserving bounded vanilla delegate noise caves; worldMinY={}, maxY={}, delegateRange={}..{}, seaLevel={}, preserveDelegateNoiseCaves={}, vanillaCarversEnabled={}. This debug path may copy vanilla density voids.",
                    worldMinY,
                    maxY,
                    caveMask.delegateMinY(),
                    caveMask.delegateMaxY(),
                    seaLevel,
                    PRESERVE_DELEGATE_NOISE_CAVES,
                    ENABLE_VANILLA_CARVERS
                );
            } else {
                UkGeoMod.LOGGER.info(
                    "Using solid UKGeo terrain with no delegate noise cave preservation; worldMinY={}, maxY={}, seaLevel={}, preserveDelegateNoiseCaves={}, vanillaCarversEnabled={}",
                    worldMinY,
                    maxY,
                    seaLevel,
                    PRESERVE_DELEGATE_NOISE_CAVES,
                    ENABLE_VANILLA_CARVERS
                );
            }
        }
    }

    private static boolean isExcludedBiomeFeature(Holder<PlacedFeature> feature) {
        Optional<ResourceKey<PlacedFeature>> key = feature.unwrapKey();
        if (key.isEmpty()) {
            return false;
        }
        String path = key.get().location().getPath().toLowerCase(Locale.ROOT);
        return path.contains("freeze")
            || path.contains("frozen")
            || path.contains("ice")
            || path.contains("snow")
            || path.contains("icicle");
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunk) {
        long fillStartNanos = System.nanoTime();
        RuntimeData data = data();
        if (data == null) {
            return PRESERVE_DELEGATE_NOISE_CAVES ? caveDelegate
                .map(delegate -> delegate.fillFromNoise(blender, randomState, structureManager, chunk))
                .orElseGet(() -> CompletableFuture.completedFuture(chunk)) : CompletableFuture.completedFuture(chunk);
        }
        /*
         * Pipeline: compute UK height/biome data, overlay UK terrain/surface/water, prime
         * heightmaps, then let the normal carver stage apply vanilla-style legacy carvers.
         * Delegate noise caves are opt-in only because copying arbitrary delegate AIR can
         * preserve broad density voids in this tall custom dimension.
         */
        boolean preserveVanillaCaves = PRESERVE_DELEGATE_NOISE_CAVES && caveDelegate.isPresent();
        CompletableFuture<ChunkAccess> baseNoise = preserveVanillaCaves ? caveDelegate
            .map(delegate -> delegate.fillFromNoise(blender, randomState, structureManager, chunk))
            .orElseGet(() -> CompletableFuture.completedFuture(chunk)) : CompletableFuture.completedFuture(chunk);
        int delegateMinY = caveDelegate.map(NoiseBasedChunkGenerator::getMinY).orElse(chunk.getMinBuildHeight());
        int delegateMaxY = caveDelegate.isPresent() ? Math.min(chunk.getMaxBuildHeight() - 1, VANILLA_MAX_Y) : chunk.getMinBuildHeight() - 1;
        ChunkTerrainPlanner.CaveMask caveMask = preserveVanillaCaves ? caveMask(delegateMinY, delegateMaxY, true) : ChunkTerrainPlanner.CaveMask.none();
        logCaveMode(caveMask, chunk.getMinBuildHeight(), chunk.getMaxBuildHeight() - 1, this.seaLevelY);
        if (DEBUG_CAVES) {
            UkGeoMod.LOGGER.info(
                "UKGeo cave debug chunk={} preserveDelegateNoiseCaves={} ranDelegateFillFromNoise={} caveMaskUsesDelegate={} delegateRange={}..{} vanillaCarversEnabled={}",
                chunk.getPos(),
                PRESERVE_DELEGATE_NOISE_CAVES,
                preserveVanillaCaves,
                caveMask.usesDelegate(),
                delegateMinY,
                delegateMaxY,
                ENABLE_VANILLA_CARVERS
            );
        }
        return baseNoise.thenCompose(noiseChunk -> CompletableFuture
                .supplyAsync(
                    Util.wrapThreadWithTaskName("ukgeo_plan", () -> {
                        try {
                            return ChunkTerrainPlanner.compute(this, data, noiseChunk);
                        } catch (IOException ex) {
                            throw new RuntimeException("Failed to plan UKGeo chunk " + noiseChunk.getPos(), ex);
                        }
                    }),
                    Util.backgroundExecutor()
                )
            .thenApply(plan -> {
                chunkPlans.put(noiseChunk.getPos().toLong(), plan);
                trimChunkPlanMap(chunkPlans, noiseChunk.getPos());
                long applyStartNanos = System.nanoTime();
                ChunkTerrainPlanner.apply(plan, noiseChunk, caveMask);
                logTiming("fillFromNoise.apply", noiseChunk.getPos(), applyStartNanos);
                long elapsedNanos = System.nanoTime() - fillStartNanos;
                PERF.recordFill(elapsedNanos);
                logTiming("fillFromNoise.total", noiseChunk.getPos(), fillStartNanos);
                logSlowTiming("fillFromNoise.total", noiseChunk.getPos(), elapsedNanos, SLOW_FILL_FROM_NOISE_WARN_MS);
                return noiseChunk;
            }));
    }

    private static ChunkTerrainPlanner.CaveMask caveMask(int delegateMinY, int delegateMaxY, boolean usingDelegate) {
        return new ChunkTerrainPlanner.CaveMask(usingDelegate, delegateMinY, delegateMaxY);
    }

    int sampleMargin() {
        return Math.max(WATER_EDGE_SMOOTHING_RADIUS, highlandSmoothingRadius) + riverWidenRadius + BORDER_MARGIN_EXTRA;
    }

    int seaLevel() {
        return seaLevelY;
    }

    private static final int BORDER_MARGIN_EXTRA = 4;

    private int riverBedY(int surfaceY, int minBuildY, int depth) {
        return Math.max(minBuildY + 1, surfaceY - depth);
    }

    private int riverWaterSurfaceY(RuntimeData data, HeightTileWindow heightWindow, int x, int z, int originalSurfaceY, int riverBedY, int waterRadius, double slope, WaterShapeCache cache) {
        OptionalInt reachSurface = riverReachWaterSurfaceY(data, heightWindow, x, z, originalSurfaceY, waterRadius, cache);
        int smoothedSurface = smoothedSurfaceY(heightWindow, x, z, Math.max(2, riverWidenRadius + 2)).orElse(originalSurfaceY);
        int target = reachSurface.orElse(Math.round((originalSurfaceY * 0.45f) + (smoothedSurface * 0.55f))) - 1;
        int minAllowedY = Math.min(target, riverBedY + 1);
        int maxAllowedY = Math.max(originalSurfaceY, minAllowedY);
        return safeClampY(target, minAllowedY, maxAllowedY);
    }

    private OptionalInt riverReachWaterSurfaceY(RuntimeData data, HeightTileWindow heightWindow, int x, int z, int fallbackSurfaceY, int waterRadius, WaterShapeCache cache) {
        int radius = Math.max(RIVER_REACH_SMOOTHING_RADIUS, Math.min(12, waterRadius + 3));
        int total = 0;
        int count = 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int distanceSquared = dx * dx + dz * dz;
                if (distanceSquared > radius * radius || riverLayerValue(data, x + dx, z + dz, cache) <= 0) {
                    continue;
                }
                int sample = sampleDecimetresOrNodata(heightWindow, x + dx, z + dz);
                if (sample == R16HeightTileLayer.NODATA) {
                    continue;
                }
                int surface = rawSurfaceY(heightWindow, x + dx, z + dz, sample);
                total += surface;
                min = Math.min(min, surface);
                max = Math.max(max, surface);
                count++;
            }
        }
        if (count < 3) {
            return OptionalInt.empty();
        }
        int average = Math.round((float) total / count);
        int clamped = safeClampY(average, min + MAX_RIVER_SURFACE_STEP, max - MAX_RIVER_SURFACE_STEP);
        return OptionalInt.of(safeClampY(clamped, fallbackSurfaceY - 3, fallbackSurfaceY + 3));
    }

    private static int safeClampY(int value, int minY, int maxY) {
        if (minY <= maxY) {
            return Math.clamp(value, minY, maxY);
        }
        if (value <= maxY) {
            return maxY;
        }
        if (value >= minY) {
            return minY;
        }
        return (minY + maxY) >> 1;
    }

    RiverShape computeRiverShape(RuntimeData data, HeightTileWindow heightWindow, int x, int z, int originalSurfaceY, int minBuildY) {
        return computeRiverShape(data, heightWindow, x, z, originalSurfaceY, minBuildY, null);
    }

    RiverShape computeRiverShape(RuntimeData data, HeightTileWindow heightWindow, int x, int z, int originalSurfaceY, int minBuildY, WaterShapeCache cache) {
        if (data.riverLayer == null) {
            return RiverShape.none(originalSurfaceY);
        }
        int searchRadius = riverSearchRadius(data);
        RiverDistance distance = nearestRiver(data, x, z, searchRadius + 2, cache);
        if (!distance.found()) {
            return RiverShape.none(originalSurfaceY);
        }
        int waterRadius = Math.max(riverWidenRadius, distance.halfWidth());
        int bankRadius = waterRadius + 2;
        if (distance.blocks() > bankRadius) {
            return RiverShape.none(originalSurfaceY);
        }
        int vegetationClass = vegetationClassAt(data, x, z, cache);
        double slope = localSurfaceSlope(heightWindow, x, z, originalSurfaceY, cache);
        boolean riverWater = riverLayerValue(data, x, z, cache) > 0 && supportedRiverWater(data, x, z, cache);
        return computeRiverShape(data, heightWindow, x, z, originalSurfaceY, minBuildY, cache, riverWater, distance, waterRadius, bankRadius, vegetationClass, slope);
    }

    private RiverShape computeRiverShape(
        RuntimeData data,
        HeightTileWindow heightWindow,
        int x,
        int z,
        int originalSurfaceY,
        int minBuildY,
        WaterShapeCache cache,
        boolean riverWater,
        RiverDistance distance,
        int waterRadius,
        int bankRadius,
        int vegetationClass,
        double slope
    ) {
        if (riverWater) {
            waterRadius = Math.max(waterRadius, riverHalfWidthValue(data, x, z, cache));
            int riverOrder = riverOrderValue(data, x, z, cache);
            double edgeDistance = riverLayerValue(data, x, z, cache) > 0
                ? nearestRiverBank(data, x, z, Math.max(4, waterRadius + 5), cache)
                : Math.max(1.0, waterRadius + 0.5 - distance.blocks());
            int depth = riverDepth(data, heightWindow, x, z, edgeDistance, slope, waterRadius, riverOrder, cache);
            int bed = riverBedY(originalSurfaceY, minBuildY, depth);
            double flowStrength = Math.clamp((waterRadius + 0.5 - distance.blocks()) / Math.max(1.0, waterRadius + 0.5), 0.0, 1.0);
            BlockState floor = waterFloorMaterial(x, z, vegetationClass, slope, true, flowStrength, depth, edgeDistance);
            int waterSurface = riverWaterSurfaceY(data, heightWindow, x, z, originalSurfaceY, bed, waterRadius, slope, cache);
            bed = Math.max(minBuildY + 1, Math.min(bed, waterSurface - Math.max(1, depth - 1)));
            return new RiverShape(true, true, bed, waterSurface, floor);
        }
        int bankDrop = riverBankDrop(distance.blocks(), waterRadius, bankRadius);
        if (bankDrop <= 0) {
            return RiverShape.none(originalSurfaceY);
        }
        int terrainSurfaceY = Math.max(minBuildY + 1, originalSurfaceY - bankDrop);
        BlockState floor = waterFloorMaterial(x, z, vegetationClass, slope, true, 0.35, 1, distance.blocks());
        return new RiverShape(false, true, terrainSurfaceY, terrainSurfaceY, floor);
    }

    RiverShape computeSurfaceWaterShape(RuntimeData data, HeightTileWindow heightWindow, int x, int z, int originalSurfaceY, int minBuildY, int vegetationClass) {
        return computeSurfaceWaterShape(data, heightWindow, x, z, originalSurfaceY, minBuildY, vegetationClass, null);
    }

    RiverShape computeSurfaceWaterShape(RuntimeData data, HeightTileWindow heightWindow, int x, int z, int originalSurfaceY, int minBuildY, int vegetationClass, WaterShapeCache cache) {
        if (!hasHeightData(data, heightWindow, x, z, cache)) {
            logHeightBoundsSuppressed(x, z);
            return RiverShape.none(originalSurfaceY);
        }
        long key = cacheKey(x, z);
        if (cache != null) {
            RiverShape cached = cache.shapes.get(key);
            if (cached != null) {
                return cached;
            }
        }
        RiverShape shape;
        if (vegetationClass != VEGETATION_FRESHWATER) {
            shape = computeRiverShape(data, heightWindow, x, z, originalSurfaceY, minBuildY, cache);
            if (!shape.influenced()) {
                shape = computeLakeBankShape(data, heightWindow, x, z, originalSurfaceY, minBuildY, vegetationClass, cache);
            }
        } else if (isRiverArtifactFreshwater(data, x, z, cache)) {
            RiverDistance distance = nearestRiver(data, x, z, riverSearchRadius(data) + 2, cache);
            int waterRadius = Math.max(riverWidenRadius, distance.halfWidth());
            int vegetation = vegetationClassAt(data, x, z, cache);
            double slope = localSurfaceSlope(heightWindow, x, z, originalSurfaceY, cache);
            shape = computeRiverShape(data, heightWindow, x, z, originalSurfaceY, minBuildY, cache, true, distance, waterRadius, waterRadius + 2, vegetation, slope);
        } else {
            int waterSurface = originalSurfaceY;
            double edgeDistance = nearestLakeEdge(data, x, z, LAKE_EDGE_SEARCH_RADIUS, cache);
            int depth = lakeDepth(data, heightWindow, x, z, cache);
            int bed = Math.max(minBuildY + 1, waterSurface - depth);
            double slope = localSurfaceSlope(heightWindow, x, z, originalSurfaceY, cache);
            BlockState floor = waterFloorMaterial(x, z, vegetationClass, slope, false, 0.0, depth, edgeDistance);
            shape = new RiverShape(true, true, bed, waterSurface, floor);
        }
        if (cache != null) {
            cache.shapes.put(key, shape);
        }
        debugWaterPlan(data, heightWindow, x, z, originalSurfaceY, minBuildY, vegetationClass, shape, cache);
        return shape;
    }

    private void debugWaterPlan(
        RuntimeData data,
        HeightTileWindow heightWindow,
        int x,
        int z,
        int originalSurfaceY,
        int minBuildY,
        int vegetationClass,
        RiverShape shape,
        WaterShapeCache cache
    ) {
        if (!DEBUG_WATER_PLAN || Math.abs(x - DEBUG_WATER_X) > DEBUG_WATER_RADIUS || Math.abs(z - DEBUG_WATER_Z) > DEBUG_WATER_RADIUS) {
            return;
        }
        RiverDistance river = data.riverLayer == null ? RiverDistance.none() : nearestRiver(data, x, z, riverSearchRadius(data) + 2, cache);
        int riverHalfWidth = Math.max(riverWidenRadius, river.halfWidth());
        boolean isRiverMask = riverLayerValue(data, x, z, cache) > 0;
        boolean isRiverInfluence = river.found() && river.blocks() <= riverHalfWidth + 2.0;
        boolean isFreshwater = vegetationClass == VEGETATION_FRESHWATER;
        boolean discardedLakeArtifact = isFreshwater && isRiverArtifactFreshwater(data, x, z, cache);
        boolean realLake = isFreshwater && !discardedLakeArtifact;
        String owner = !shape.hasWater()
            ? "NONE"
            : originalSurfaceY < seaLevelY
                ? "SEA"
                : realLake
                    ? "REAL_LAKE"
                    : "RIVER";
        String plannedAtY = DEBUG_WATER_Y > shape.terrainSurfaceY() && DEBUG_WATER_Y <= shape.waterSurfaceY()
            ? "WATER"
            : DEBUG_WATER_Y == shape.terrainSurfaceY()
                ? "FLOOR"
                : "OTHER";
        UkGeoMod.LOGGER.info(
            "UKGeo water debug x={} z={} y={} owner={} riverMask={} riverInfluence={} freshwater={} realLake={} discardedLakeArtifact={} riverHalfWidth={} riverOrder={} riverDistance={} finalSurfaceY={} finalFloorY={} floorMaterial={} plannedAtY={} originalSurfaceY={} minBuildY={}",
            x,
            z,
            DEBUG_WATER_Y,
            owner,
            isRiverMask,
            isRiverInfluence,
            isFreshwater,
            realLake,
            discardedLakeArtifact,
            riverHalfWidth,
            riverOrderValue(data, x, z, cache),
            river.found() ? river.blocks() : -1.0,
            shape.waterSurfaceY(),
            shape.terrainSurfaceY(),
            shape.floorMaterial(),
            plannedAtY,
            originalSurfaceY,
            minBuildY
        );
    }

    private RiverShape computeLakeBankShape(
        RuntimeData data,
        HeightTileWindow heightWindow,
        int x,
        int z,
        int originalSurfaceY,
        int minBuildY,
        int vegetationClass,
        WaterShapeCache cache
    ) {
        LakeDistance lake = nearestEffectiveLake(data, heightWindow, x, z, LAKE_BANK_BLEND_RADIUS, cache);
        if (!lake.found() || lake.blocks() <= 0.0 || lake.blocks() > LAKE_BANK_BLEND_RADIUS + 0.35) {
            return RiverShape.none(originalSurfaceY);
        }
        double slope = localSurfaceSlope(heightWindow, x, z, originalSurfaceY, cache);
        int normalBankTop = lake.waterSurfaceY() + (slope > 1.9 || vegetationClass == VEGETATION_ROCKY ? 2 : 1);
        double blend = 1.0 - smoothstep(lake.blocks() / (LAKE_BANK_BLEND_RADIUS + 0.35));
        double noise = valueNoise(x, z, 0.18, 0x4c414b4553484f52L) * 0.35;
        int target = Math.round((float) lerp(originalSurfaceY, normalBankTop + noise, blend));
        int terrainSurfaceY = Math.max(minBuildY + 1, Math.min(originalSurfaceY, target));
        if (terrainSurfaceY >= originalSurfaceY) {
            return RiverShape.none(originalSurfaceY);
        }
        BlockState floor = waterFloorMaterial(x, z, vegetationClass, slope, false, 0.0, 1, lake.blocks());
        return new RiverShape(false, true, terrainSurfaceY, lake.waterSurfaceY(), floor);
    }

    private int lakeDepth(RuntimeData data, HeightTileWindow heightWindow, int x, int z, WaterShapeCache cache) {
        long key = cacheKey(x, z);
        if (cache != null) {
            Integer cached = cache.lakeDepths.get(key);
            if (cached != null) {
                return cached;
            }
        }
        double total = 0.0;
        int count = 0;
        for (int dz = -LAKE_DEPTH_SMOOTHING_RADIUS; dz <= LAKE_DEPTH_SMOOTHING_RADIUS; dz++) {
            for (int dx = -LAKE_DEPTH_SMOOTHING_RADIUS; dx <= LAKE_DEPTH_SMOOTHING_RADIUS; dx++) {
                if (!isEffectiveLakeCell(data, x + dx, z + dz, cache)) {
                    continue;
                }
                total += rawLakeDepth(data, heightWindow, x + dx, z + dz, cache);
                count++;
            }
        }
        double smoothedDepth = count == 0 ? rawLakeDepth(data, heightWindow, x, z, cache) : total / count;
        double edgeDistance = nearestLakeEdge(data, x, z, LAKE_EDGE_SEARCH_RADIUS, cache);
        int depth = Math.round((float) smoothedDepth);
        if (edgeDistance <= 2.35) {
            depth = 1;
        } else if (edgeDistance <= 3.15) {
            depth = Math.min(depth, 2);
        }
        if (edgeDistance > 3.0 && depth <= 1) {
            depth = 2;
        }
        if (edgeDistance > 4.25 && depth <= 2) {
            depth = 3;
        }
        if (edgeDistance > 6.25 && depth <= 3 && lakeMaxDepth(edgeDistance) > 3) {
            depth = 4;
        }
        if (edgeDistance > 9.0 && depth <= 4 && lakeMaxDepth(edgeDistance) > 4) {
            depth = 5;
        }
        depth = Math.max(depth, riverLakeMouthDepth(data, heightWindow, x, z, edgeDistance, cache));
        depth = Math.clamp(depth, 1, lakeMaxDepth(edgeDistance));
        if (cache != null) {
            cache.lakeDepths.put(key, depth);
        }
        return depth;
    }

    private boolean isRiverArtifactFreshwater(RuntimeData data, int x, int z, WaterShapeCache cache) {
        if (data.riverLayer == null || vegetationClassAt(data, x, z, cache) != VEGETATION_FRESHWATER) {
            return false;
        }
        long key = cacheKey(x, z);
        if (cache != null) {
            Boolean cached = cache.riverArtifactFreshwater.get(key);
            if (cached != null) {
                return cached;
            }
        }
        RiverDistance seedRiver = nearestRiver(data, x, z, riverSearchRadius(data) + 2, cache);
        int seedHalfWidth = Math.max(riverWidenRadius, seedRiver.halfWidth());
        if (!seedRiver.found() || seedRiver.blocks() > seedHalfWidth + 2.0) {
            if (cache != null) {
                cache.riverArtifactFreshwater.put(key, false);
            }
            return false;
        }

        int maxCells = LAKE_ARTIFACT_MAX_COMPONENT;
        int diameter = LAKE_ARTIFACT_SCAN_RADIUS * 2 + 1;
        boolean[] visited = new boolean[diameter * diameter];
        int[] queueX = new int[maxCells + 1];
        int[] queueZ = new int[maxCells + 1];
        int head = 0;
        int tail = 0;
        int area = 0;
        int corridorOverlap = 0;
        int maxHalfWidth = Math.max(1, riverWidenRadius);
        int minX = x;
        int maxX = x;
        int minZ = z;
        int maxZ = z;
        boolean overflow = false;

        queueX[tail] = x;
        queueZ[tail] = z;
        tail++;
        visited[LAKE_ARTIFACT_SCAN_RADIUS * diameter + LAKE_ARTIFACT_SCAN_RADIUS] = true;
        while (head < tail) {
            int cx = queueX[head];
            int cz = queueZ[head];
            head++;
            area++;
            minX = Math.min(minX, cx);
            maxX = Math.max(maxX, cx);
            minZ = Math.min(minZ, cz);
            maxZ = Math.max(maxZ, cz);

            RiverDistance river = nearestRiver(data, cx, cz, riverSearchRadius(data) + 2, cache);
            int halfWidth = Math.max(riverWidenRadius, river.halfWidth());
            maxHalfWidth = Math.max(maxHalfWidth, halfWidth);
            if (river.found() && river.blocks() <= halfWidth + 1.5) {
                corridorOverlap++;
            }

            if (area >= maxCells) {
                overflow = true;
                break;
            }

            int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] offset : offsets) {
                int nx = cx + offset[0];
                int nz = cz + offset[1];
                int lx = nx - x + LAKE_ARTIFACT_SCAN_RADIUS;
                int lz = nz - z + LAKE_ARTIFACT_SCAN_RADIUS;
                if (lx < 0 || lz < 0 || lx >= diameter || lz >= diameter) {
                    overflow = true;
                    continue;
                }
                int visitedIndex = lz * diameter + lx;
                if (visited[visitedIndex] || vegetationClassAt(data, nx, nz, cache) != VEGETATION_FRESHWATER) {
                    continue;
                }
                visited[visitedIndex] = true;
                if (tail >= queueX.length) {
                    overflow = true;
                    break;
                }
                queueX[tail] = nx;
                queueZ[tail] = nz;
                tail++;
            }
            if (overflow && tail >= queueX.length) {
                break;
            }
        }

        int localRiverFullWidth = maxHalfWidth * 2 + 1;
        int bboxWidthX = maxX - minX + 1;
        int bboxWidthZ = maxZ - minZ + 1;
        int componentLength = Math.max(bboxWidthX, bboxWidthZ);
        int componentCrossWidth = Math.min(bboxWidthX, bboxWidthZ);
        double elongation = componentLength / (double) Math.max(1, componentCrossWidth);
        double overlapRatio = area == 0 ? 0.0 : corridorOverlap / (double) area;
        double outsideRatio = 1.0 - overlapRatio;
        boolean embeddedInRiver = corridorOverlap > 0
            && overlapRatio >= 0.60
            && componentCrossWidth <= localRiverFullWidth * 1.5;
        boolean elongatedRiverWater = corridorOverlap > 0
            && elongation >= 3.0
            && overlapRatio >= 0.45
            && componentCrossWidth <= localRiverFullWidth * 2.0;
        boolean smallRiverScaleWaterbody = corridorOverlap > 0
            && area < localRiverFullWidth * localRiverFullWidth * 2
            && componentCrossWidth <= localRiverFullWidth * 1.75;
        boolean mostlyRiverCorridor = corridorOverlap > 0
            && outsideRatio < 0.35
            && componentCrossWidth <= localRiverFullWidth * 1.75;
        boolean realLake = !embeddedInRiver
            && !elongatedRiverWater
            && !smallRiverScaleWaterbody
            && !mostlyRiverCorridor
            && (
                (overflow && overlapRatio < LAKE_ARTIFACT_CORRIDOR_OVERLAP)
                    || area >= localRiverFullWidth * localRiverFullWidth * 3
                    || componentCrossWidth >= localRiverFullWidth * 1.75
                    || overlapRatio <= 0.45
            );
        boolean artifact = !realLake && corridorOverlap > 0 && (
            embeddedInRiver
                || elongatedRiverWater
                || smallRiverScaleWaterbody
                || mostlyRiverCorridor
                || overlapRatio >= LAKE_ARTIFACT_CORRIDOR_OVERLAP
        );

        if (cache != null) {
            for (int i = 0; i < tail; i++) {
                cache.riverArtifactFreshwater.put(cacheKey(queueX[i], queueZ[i]), artifact);
            }
        }
        return artifact;
    }

    private double rawLakeDepth(RuntimeData data, HeightTileWindow heightWindow, int x, int z, WaterShapeCache cache) {
        long key = cacheKey(x, z);
        if (cache != null) {
            Double cached = cache.rawLakeDepths.get(key);
            if (cached != null) {
                return cached;
            }
        }
        double edgeDistance = nearestLakeEdge(data, x, z, LAKE_EDGE_SEARCH_RADIUS, cache);
        double maxDepth = lakeMaxDepth(edgeDistance);
        double shelfBlocks = Math.min(3.0, Math.max(1.7, edgeDistance * 0.08));
        double depth;
        if (edgeDistance <= shelfBlocks) {
            double t = edgeDistance / Math.max(1.0, shelfBlocks);
            depth = 1.0 + 0.25 * t;
        } else {
            double u = Math.clamp((edgeDistance - shelfBlocks) / 8.0, 0.0, 1.0);
            double curved = Math.pow(u, 0.72);
            depth = 1.0 + curved * (maxDepth - 1.0);
        }
        double broadUndulation = valueNoise(x, z, 0.014, 0x4c414b45L) * 0.28;
        double broadBasin = edgeDistance > 7.0 ? broadLakeBasinInfluence(x, z) * 0.45 : 0.0;
        depth += broadUndulation + broadBasin;
        if (edgeDistance > 11.0) {
            double baseWeight = smoothstep((edgeDistance - 11.0) / 8.0);
            depth = lerp(depth, maxDepth, baseWeight * 0.35);
        }
        depth = Math.clamp(depth, 1.0, maxDepth);
        if (cache != null) {
            cache.rawLakeDepths.put(key, depth);
        }
        return depth;
    }

    private int riverLakeMouthDepth(RuntimeData data, HeightTileWindow heightWindow, int x, int z, double lakeEdgeDistance, WaterShapeCache cache) {
        if (data.riverLayer == null || lakeEdgeDistance > 5.5) {
            return 1;
        }
        RiverDistance river = nearestRiver(data, x, z, 4, cache);
        if (!river.found() || river.blocks() > 3.5) {
            return 1;
        }
        double slope = localSurfaceSlope(heightWindow, x, z, computeSurfaceY(data, heightWindow, x, z), cache);
        int halfWidth = Math.max(riverWidenRadius, river.halfWidth());
        int order = Math.max(1, riverOrderValue(data, x, z, cache));
        double riverEdge = nearestRiverBank(data, x, z, Math.max(4, halfWidth + 5), cache);
        int riverDepth = riverDepth(data, heightWindow, x, z, riverEdge, slope, halfWidth, order, cache);
        double fade = smoothstep(Math.max(lakeEdgeDistance / 5.5, river.blocks() / 3.5));
        int target = riverDepth - Math.round((float) (fade * 2.0));
        return Math.clamp(target, 1, lakeMaxDepth(lakeEdgeDistance));
    }

    private double broadLakeBasinInfluence(int x, int z) {
        int cellSize = 56;
        int cellX = Math.floorDiv(x, cellSize);
        int cellZ = Math.floorDiv(z, cellSize);
        double best = 0.0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int cx = cellX + dx;
                int cz = cellZ + dz;
                double centerX = (cx + hashUnit(cx, cz, 0x424153494e58L)) * cellSize;
                double centerZ = (cz + hashUnit(cx, cz, 0x424153494e5aL)) * cellSize;
                double distance = Math.hypot(x - centerX, z - centerZ);
                double radius = 18.0 + hashUnit(cx, cz, 0x424153494e52L) * 18.0;
                double influence = 1.0 - smoothstep(distance / radius);
                best = Math.max(best, influence);
            }
        }
        return best;
    }

    private int lakeMaxDepth(double edgeDistance) {
        if (edgeDistance <= 3.0) {
            return 2;
        }
        if (edgeDistance <= 4.5) {
            return 3;
        }
        if (edgeDistance <= 7.0) {
            return 4;
        }
        if (edgeDistance <= 10.5) {
            return 5;
        }
        if (edgeDistance <= 15.5) {
            return 6;
        }
        if (edgeDistance <= 21.5) {
            return 7;
        }
        return 8;
    }

    private double nearestLakeEdge(RuntimeData data, int x, int z, int radius) {
        return nearestLakeEdge(data, x, z, radius, null);
    }

    private double nearestLakeEdge(RuntimeData data, int x, int z, int radius, WaterShapeCache cache) {
        long key = cacheKey(x, z);
        if (cache != null) {
            Double cached = cache.lakeEdgeDistances.get(key);
            if (cached != null) {
                return cached;
            }
        }
        double bestDistanceSquared = Double.POSITIVE_INFINITY;
        int radiusSquared = radius * radius;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int distanceSquared = dx * dx + dz * dz;
                if (distanceSquared > radiusSquared) {
                    continue;
                }
                if (isEffectiveLakeCell(data, x + dx, z + dz, cache)) {
                    continue;
                }
                bestDistanceSquared = Math.min(bestDistanceSquared, distanceSquared);
            }
        }
        double distance = Double.isInfinite(bestDistanceSquared) ? radius : Math.sqrt(bestDistanceSquared);
        if (cache != null) {
            cache.lakeEdgeDistances.put(key, distance);
        }
        return distance;
    }

    private boolean isEffectiveLakeCell(RuntimeData data, int x, int z, WaterShapeCache cache) {
        return vegetationClassAt(data, x, z, cache) == VEGETATION_FRESHWATER && !isRiverArtifactFreshwater(data, x, z, cache);
    }

    private LakeDistance nearestEffectiveLake(RuntimeData data, HeightTileWindow heightWindow, int x, int z, int radius, WaterShapeCache cache) {
        double bestDistanceSquared = Double.POSITIVE_INFINITY;
        int bestWaterSurfaceY = Integer.MIN_VALUE;
        int radiusSquared = radius * radius;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int distanceSquared = dx * dx + dz * dz;
                if (distanceSquared == 0 || distanceSquared > radiusSquared) {
                    continue;
                }
                int sampleX = x + dx;
                int sampleZ = z + dz;
                if (!isEffectiveLakeCell(data, sampleX, sampleZ, cache) || distanceSquared >= bestDistanceSquared) {
                    continue;
                }
                int sample = sampleDecimetresOrNodata(heightWindow, sampleX, sampleZ);
                if (sample == R16HeightTileLayer.NODATA) {
                    continue;
                }
                bestDistanceSquared = distanceSquared;
                bestWaterSurfaceY = waterSmoothedSurfaceY(data, heightWindow, sampleX, sampleZ, sample, rawSurfaceY(heightWindow, sampleX, sampleZ, sample));
            }
        }
        if (Double.isInfinite(bestDistanceSquared)) {
            return LakeDistance.none();
        }
        return new LakeDistance(Math.sqrt(bestDistanceSquared), bestWaterSurfaceY);
    }

    private double localSurfaceSlope(HeightTileWindow heightWindow, int x, int z, int centerSurfaceY) {
        return localSurfaceSlope(heightWindow, x, z, centerSurfaceY, null);
    }

    private double localSurfaceSlope(HeightTileWindow heightWindow, int x, int z, int centerSurfaceY, WaterShapeCache cache) {
        SurfaceSlopeKey key = new SurfaceSlopeKey(x, z, centerSurfaceY);
        if (cache != null) {
            Double cached = cache.surfaceSlopes.get(key);
            if (cached != null) {
                return cached;
            }
        }
        int maxDelta = 0;
        int step = 4;
        int[][] offsets = {{step, 0}, {-step, 0}, {0, step}, {0, -step}};
        for (int[] offset : offsets) {
            int sample = sampleDecimetresOrNodata(heightWindow, x + offset[0], z + offset[1]);
            if (sample == R16HeightTileLayer.NODATA) {
                continue;
            }
            int surface = rawSurfaceY(heightWindow, x + offset[0], z + offset[1], sample);
            maxDelta = Math.max(maxDelta, Math.abs(surface - centerSurfaceY));
        }
        double slope = maxDelta / (double) step;
        if (cache != null) {
            cache.surfaceSlopes.put(key, slope);
        }
        return slope;
    }

    private BlockState waterFloorMaterial(int x, int z, int vegetationClass, double slope, boolean river, double flowStrength, int depth, double edgeDistance) {
        double coarse = (river ? 0.2 + flowStrength * 0.45 : 0.0) + Math.clamp(slope / 1.8, 0.0, 0.35);
        double organic = 0.0;
        double sandy = 0.0;
        double rocky = Math.clamp(slope / 2.4, 0.0, 0.45);

        switch (vegetationClass) {
            case VEGETATION_WETLAND, VEGETATION_FRESHWATER -> organic += 0.55;
            case VEGETATION_BROADLEAF_WOODLAND, VEGETATION_CONIFER_WOODLAND -> organic += 0.35;
            case VEGETATION_ARABLE, VEGETATION_IMPROVED_GRASSLAND, VEGETATION_NEUTRAL_GRASSLAND -> organic += 0.18;
            case VEGETATION_HEATH, VEGETATION_ACID_GRASSLAND, VEGETATION_CALCAREOUS_GRASSLAND -> sandy += 0.35;
            case VEGETATION_ROCKY -> rocky += 0.55;
            default -> sandy += 0.15;
        }

        double sedimentZone = valueNoise(x, z, river ? 0.055 : 0.032, 0x534544494d454e54L);
        double secondaryZone = valueNoise(x, z, river ? 0.08 : 0.045, 0x464c4f4f52L);
        if (!river) {
            sandy += edgeDistance <= 4.5 ? 0.28 : 0.0;
            organic += depth >= 3 ? 0.18 : 0.0;
            organic += depth >= 5 ? 0.2 : 0.0;
            coarse *= edgeDistance <= 3.5 ? 1.15 : 0.65;
        }
        if (rocky + coarse > 0.85 && sedimentZone > -0.35) {
            return secondaryZone > 0.25 ? Blocks.STONE.defaultBlockState() : Blocks.GRAVEL.defaultBlockState();
        }
        if (coarse > 0.5 && sedimentZone > -0.55) {
            return secondaryZone > -0.1 ? Blocks.GRAVEL.defaultBlockState() : Blocks.COARSE_DIRT.defaultBlockState();
        }
        if (sandy > organic && sedimentZone > -0.25) {
            return secondaryZone > 0.45 ? Blocks.GRAVEL.defaultBlockState() : Blocks.SAND.defaultBlockState();
        }
        if (organic > 0.45) {
            if (depth >= 4 || sedimentZone > 0.35) {
                return Blocks.CLAY.defaultBlockState();
            }
            return secondaryZone > -0.35 ? Blocks.MUD.defaultBlockState() : Blocks.DIRT.defaultBlockState();
        }
        if (depth >= 3 && sedimentZone > 0.15) {
            return Blocks.CLAY.defaultBlockState();
        }
        return secondaryZone > 0.35 ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.DIRT.defaultBlockState();
    }

    private static double valueNoise(int x, int z, double frequency, long salt) {
        int ix = (int) Math.floor(x * frequency);
        int iz = (int) Math.floor(z * frequency);
        double fx = x * frequency - ix;
        double fz = z * frequency - iz;
        double sx = smoothstep(fx);
        double sz = smoothstep(fz);
        double a = hashNoise(ix, iz, salt);
        double b = hashNoise(ix + 1, iz, salt);
        double c = hashNoise(ix, iz + 1, salt);
        double d = hashNoise(ix + 1, iz + 1, salt);
        return lerp(lerp(a, b, sx), lerp(c, d, sx), sz);
    }

    private static double hashNoise(int x, int z, long salt) {
        long value = salt;
        value ^= x * 0x9E3779B97F4A7C15L;
        value ^= z * 0xC2B2AE3D27D4EB4FL;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return ((value >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
    }

    private static double hashUnit(int x, int z, long salt) {
        return (hashNoise(x, z, salt) + 1.0) * 0.5;
    }

    private int riverDepth(RuntimeData data, HeightTileWindow heightWindow, int x, int z, double edgeDistance, double slope, int halfWidth, int order, WaterShapeCache cache) {
        long key = cacheKey(x, z);
        if (cache != null) {
            Integer cached = cache.riverDepths.get(key);
            if (cached != null) {
                return cached;
            }
        }
        int depth = rawRiverDepth(x, z, edgeDistance, slope, halfWidth, order);
        depth = Math.clamp(depth, 1, riverMaxDepth(edgeDistance, slope, order));
        if (cache != null) {
            cache.riverDepths.put(key, depth);
        }
        return depth;
    }

    private int rawRiverDepth(int x, int z, double edgeDistance, double slope, int halfWidth, int order) {
        int maxDepth = riverMaxDepth(edgeDistance, slope, order);
        int blocksFromBank = Math.max(1, (int) Math.floor(edgeDistance + 0.01));
        int shelfBlocks = halfWidth >= 12 ? 2 : 1;
        int visibleDepth = blocksFromBank <= shelfBlocks ? 1 : blocksFromBank - shelfBlocks + 1;
        if (blocksFromBank > 4) {
            double run = valueNoise(x, z, 0.018, 0x524956455252554eL);
            visibleDepth += run > 0.35 ? 1 : 0;
        }
        int depth = visibleDepth + 1;
        return Math.clamp(depth, 1, maxDepth);
    }

    private int riverMaxDepth(double edgeDistance, double slope, int order) {
        int widthDepth = edgeDistance <= 1.0 ? 2 : edgeDistance <= 2.0 ? 3 : edgeDistance <= 3.0 ? 4 : edgeDistance <= 4.0 ? 5 : 6;
        int orderDepth = switch (Math.max(1, order)) {
            case 1 -> 3;
            case 2 -> 4;
            case 3 -> 5;
            case 4 -> 6;
            default -> 8;
        };
        widthDepth = Math.min(widthDepth, orderDepth);
        if (slope > 1.2 && edgeDistance > 4.0) {
            widthDepth = Math.min(widthDepth + 1, orderDepth);
        }
        return widthDepth;
    }

    private double nearestRiverBank(RuntimeData data, int x, int z, int radius, WaterShapeCache cache) {
        long key = cacheKey(x, z);
        if (cache != null) {
            Double cached = cache.riverBankDistances.get(key);
            if (cached != null) {
                return cached;
            }
        }
        double bestDistanceSquared = Double.POSITIVE_INFINITY;
        int radiusSquared = radius * radius;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int distanceSquared = dx * dx + dz * dz;
                if (distanceSquared > radiusSquared) {
                    continue;
                }
                if (riverLayerValue(data, x + dx, z + dz, cache) > 0) {
                    continue;
                }
                bestDistanceSquared = Math.min(bestDistanceSquared, distanceSquared);
            }
        }
        double distance = Double.isInfinite(bestDistanceSquared) ? radius : Math.sqrt(bestDistanceSquared);
        if (cache != null) {
            cache.riverBankDistances.put(key, distance);
        }
        return distance;
    }

    private int riverSearchRadius(RuntimeData data) {
        return Math.max(riverWidenRadius, data.manifest.maxRiverHalfWidth) + 2;
    }

    private int riverHalfWidthValue(RuntimeData data, int x, int z, WaterShapeCache cache) {
        if (data.riverHalfWidthLayer == null) {
            return riverWidenRadius;
        }
        if (!hasHeightData(data, null, x, z, cache)) {
            return 0;
        }
        long key = cacheKey(x, z);
        if (cache != null) {
            Integer cached = cache.riverHalfWidths.get(key);
            if (cached != null) {
                return cached;
            }
        }
        int value = data.riverHalfWidthLayer.sampleOrDefault(x, z, 0);
        if (cache != null) {
            cache.riverHalfWidths.put(key, value);
        }
        return value;
    }

    private int riverOrderValue(RuntimeData data, int x, int z, WaterShapeCache cache) {
        if (data.riverOrderLayer == null) {
            return 3;
        }
        if (!hasHeightData(data, null, x, z, cache)) {
            return 0;
        }
        long key = cacheKey(x, z);
        if (cache != null) {
            Integer cached = cache.riverOrders.get(key);
            if (cached != null) {
                return cached;
            }
        }
        int value = Math.max(1, data.riverOrderLayer.sampleOrDefault(x, z, 1));
        if (cache != null) {
            cache.riverOrders.put(key, value);
        }
        return value;
    }

    private boolean supportedRiverWater(RuntimeData data, int x, int z, WaterShapeCache cache) {
        int support = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (riverLayerValue(data, x + dx, z + dz, cache) > 0) {
                    support++;
                }
            }
        }
        return support >= 3;
    }

    private int riverLayerValue(RuntimeData data, int x, int z) {
        return riverLayerValue(data, x, z, null);
    }

    private int riverLayerValue(RuntimeData data, int x, int z, WaterShapeCache cache) {
        if (data.riverLayer == null) {
            return 0;
        }
        if (!hasHeightData(data, null, x, z, cache)) {
            return 0;
        }
        long key = cacheKey(x, z);
        if (cache != null) {
            Integer cached = cache.riverValues.get(key);
            if (cached != null) {
                return cached;
            }
        }
        int value = data.riverLayer.sampleOrDefault(x, z, 0);
        if (cache != null) {
            cache.riverValues.put(key, value);
        }
        return value;
    }

    private int vegetationClassAt(RuntimeData data, int x, int z, WaterShapeCache cache) {
        long key = cacheKey(x, z);
        if (cache != null) {
            Integer cached = cache.vegetationValues.get(key);
            if (cached != null) {
                return cached;
            }
        }
        int value = hasHeightData(data, null, x, z, cache) ? sampleVegetationClass(data, x, z) : 0;
        if (cache != null) {
            cache.vegetationValues.put(key, value);
        }
        return value;
    }

    private static long cacheKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    static final class WaterShapeCache {
        private final Map<Long, RiverShape> shapes = new HashMap<>();
        private final Map<Long, Integer> lakeDepths = new HashMap<>();
        private final Map<Long, Double> rawLakeDepths = new HashMap<>();
        private final Map<Long, Integer> riverDepths = new HashMap<>();
        private final Map<Long, Integer> vegetationValues = new HashMap<>();
        private final Map<Long, Integer> riverValues = new HashMap<>();
        private final Map<Long, Integer> riverHalfWidths = new HashMap<>();
        private final Map<Long, Integer> riverOrders = new HashMap<>();
        private final Map<Long, Boolean> heightData = new HashMap<>();
        private final Map<Long, Boolean> riverArtifactFreshwater = new HashMap<>();
        private final Map<Long, Double> lakeEdgeDistances = new HashMap<>();
        private final Map<Long, Double> riverBankDistances = new HashMap<>();
        private final Map<NearestRiverKey, RiverDistance> nearestRivers = new HashMap<>();
        private final Map<SurfaceSlopeKey, Double> surfaceSlopes = new HashMap<>();
    }

    private record NearestRiverKey(int x, int z, int radius) {
    }

    private record SurfaceSlopeKey(int x, int z, int centerSurfaceY) {
    }

    private record BaseQueryKey(int x, int z, int minBuildY) {
    }

    private record BaseColumnPlan(int surfaceY, int vegetationClass, RiverShape river, BlockState surfaceRock, BlockState exposedSurfaceRock) {
    }

    int sampleVegetationClass(RuntimeData data, int x, int z) {
        if (data.vegetationLayer == null) {
            return 0;
        }
        return data.vegetationLayer.sampleOrDefault(x, z, 0);
    }

    boolean hasHeightData(RuntimeData data, HeightTileWindow heightWindow, int x, int z) {
        return hasHeightData(data, heightWindow, x, z, null);
    }

    private boolean hasHeightData(RuntimeData data, HeightTileWindow heightWindow, int x, int z, WaterShapeCache cache) {
        if (data == null || data.height == null) {
            return false;
        }
        long key = cacheKey(x, z);
        if (cache != null) {
            Boolean cached = cache.heightData.get(key);
            if (cached != null) {
                return cached;
            }
        }
        boolean valid = heightWindow != null
            ? heightWindow.decimetresOrNodata(x, z) != HeightTileWindow.NODATA
            : data.height.sampleDecimetresOrNodata(x, z) != R16HeightTileLayer.NODATA;
        if (!valid) {
            logHeightBoundsSuppressed(x, z);
        }
        if (cache != null) {
            cache.heightData.put(key, valid);
        }
        return valid;
    }

    private static void logHeightBoundsSuppressed(int x, int z) {
        if (!DEBUG_HEIGHT_BOUNDS || debugHeightBoundsLogs.getAndIncrement() >= MAX_DEBUG_HEIGHT_BOUNDS_LOGS) {
            return;
        }
        UkGeoMod.LOGGER.info("UKGeo height-bounds debug x={} z={}: height=NODATA/ocean, surface/vegetation/river disabled, ores still allowed", x, z);
    }

    BlockState sampleSurfaceRock(RuntimeData data, int x, int z, int y) {
        if (data.surfaceLayer == null) {
            return defaultBaseRock(y);
        }
        int classId = data.surfaceLayer.sampleOrDefault(x, z, 0);
        if (classId == 0) {
            return defaultBaseRock(y);
        }
        return surfaceBlockCache.computeIfAbsent(classId, id -> resolveSurfaceBlock(data, id, y));
    }

    BlockState defaultBaseRock(int y) {
        return y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }

    ChunkTerrainPlanner.OrePlacement[] buildOrePlacements(RuntimeData data, ChunkAccess chunk, ChunkTerrainPlanner.ColumnPlan[] columns) {
        ChunkPos pos = chunk.getPos();
        long seed = (((long) pos.x) << 32) ^ (pos.z & 0xffffffffL) ^ 0x554b47454f4cL;
        java.util.Random random = new java.util.Random(seed);
        List<ChunkTerrainPlanner.OrePlacement> placements = new ArrayList<>();
        for (OreDefinition ore : data.ores) {
            U8OreTileLayer scoreLayer = ore.hasScoreLayer() ? data.oreLayers.get(ore.scoreLayer()) : null;
            Optional<BlockStatePair> states = resolveOreBlocks(ore);
            if (states.isEmpty()) {
                continue;
            }
            int bandMin = scaledVanillaYFloor(ore.vanillaMinY(), chunk);
            int bandMax = scaledVanillaYCeil(ore.vanillaMaxY(), chunk);
            int scaledPeakY = switch (ore.heightProfile()) {
                case UNIFORM, DEEP_BIASED -> Integer.MIN_VALUE;
                case TRIANGLE, TWO_PEAKS -> scaledVanillaY(ore.vanillaPeakY(), chunk);
            };
            int score = scoreLayer == null ? 0 : scoreLayer.sampleOrDefault(pos.getMinBlockX() + 8, pos.getMinBlockZ() + 8, 0);
            double normalAttempts = normalOreAttempts(ore, score);
            int attempts = scaledOreAttempts(normalAttempts, score > 0, random);
            logOreHeightProfile(ore, chunk, normalAttempts, score > 0);
            int skippedBandMisses = 0;
            int acceptedCenters = 0;
            int plannedPlacements = 0;
            int minTerrainTop = Integer.MAX_VALUE;
            int maxTerrainTop = Integer.MIN_VALUE;
            for (int attempt = 0; attempt < attempts; attempt++) {
                int localX = random.nextInt(16);
                int localZ = random.nextInt(16);
                ChunkTerrainPlanner.ColumnPlan column = columns[localZ * 16 + localX];
                int top = column.terrainTop();
                minTerrainTop = Math.min(minTerrainTop, top);
                maxTerrainTop = Math.max(maxTerrainTop, top);
                int min = Math.max(chunk.getMinBuildHeight() + 1, bandMin);
                int max = Math.min(top - 1, bandMax);
                if (min > max) {
                    skippedBandMisses++;
                    continue;
                }
                int y = min + random.nextInt(max - min + 1);
                if (!acceptOreHeight(ore, y, bandMin, bandMax, chunk, random)) {
                    continue;
                }
                acceptedCenters++;
                for (int i = 0; i < ore.veinSize(); i++) {
                    int px = Math.clamp(localX + random.nextInt(5) - 2, 0, 15);
                    int pz = Math.clamp(localZ + random.nextInt(5) - 2, 0, 15);
                    int py = Math.clamp(y + random.nextInt(5) - 2, chunk.getMinBuildHeight() + 1, chunk.getMaxBuildHeight() - 1);
                    if (oreHeightWeight(ore, py, bandMin, bandMax, chunk) <= 0.0) {
                        continue;
                    }
                    BlockState oreState = py < 0 ? states.get().deepslate : states.get().normal;
                    placements.add(new ChunkTerrainPlanner.OrePlacement(px, py, pz, oreState));
                    plannedPlacements++;
                }
            }
            logOrePlacementDebug(ore, chunk, score, normalAttempts, attempts, bandMin, bandMax, scaledPeakY, skippedBandMisses, acceptedCenters, plannedPlacements, minTerrainTop, maxTerrainTop);
        }
        return placements.toArray(ChunkTerrainPlanner.OrePlacement[]::new);
    }

    private RiverDistance nearestRiver(RuntimeData data, int x, int z, int radius) {
        return nearestRiver(data, x, z, radius, null);
    }

    private RiverDistance nearestRiver(RuntimeData data, int x, int z, int radius, WaterShapeCache cache) {
        NearestRiverKey key = new NearestRiverKey(x, z, radius);
        if (cache != null) {
            RiverDistance cached = cache.nearestRivers.get(key);
            if (cached != null) {
                return cached;
            }
        }
        int bestDistanceSquared = Integer.MAX_VALUE;
        int bestScore = 0;
        int bestHalfWidth = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int distanceSquared = dx * dx + dz * dz;
                if (distanceSquared > radius * radius) {
                    continue;
                }
                int score = riverLayerValue(data, x + dx, z + dz, cache);
                if (score <= 0) {
                    continue;
                }
                if (distanceSquared < bestDistanceSquared || (distanceSquared == bestDistanceSquared && score > bestScore)) {
                    bestDistanceSquared = distanceSquared;
                    bestScore = score;
                    bestHalfWidth = riverHalfWidthValue(data, x + dx, z + dz, cache);
                }
            }
        }
        RiverDistance result = bestScore <= 0 ? RiverDistance.none() : new RiverDistance(Math.sqrt(bestDistanceSquared), bestScore, bestHalfWidth);
        if (cache != null) {
            cache.nearestRivers.put(key, result);
        }
        return result;
    }

    private int riverChannelDepth(double distance, int waterRadius) {
        int minimumWaterDepth = 2;
        int maximumDepth = riverCarveDepth;
        if (waterRadius > 0) {
            maximumDepth = Math.max(maximumDepth, minimumWaterDepth + 1);
        }
        if (maximumDepth <= minimumWaterDepth) {
            return minimumWaterDepth;
        }
        double radius = Math.max(1.0, waterRadius + 0.5);
        double edge = smoothstep(distance / radius);
        return minimumWaterDepth + (int) Math.round((maximumDepth - minimumWaterDepth) * (1.0 - edge));
    }

    private int riverBankDrop(double distance, int waterRadius, int bankRadius) {
        double bankWidth = Math.max(1.0, bankRadius - waterRadius);
        double bankProgress = Math.clamp((distance - waterRadius) / bankWidth, 0.0, 1.0);
        int maxBankDrop = Math.min(2, Math.max(1, riverCarveDepth - 1));
        return (int) Math.round(maxBankDrop * (1.0 - smoothstep(bankProgress)));
    }

    private BlockState surfaceGeologyBlock(int x, int z, int y) {
        RuntimeData data = data();
        if (data == null || data.surfaceLayer == null) {
            return y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
        }
        int classId = data.surfaceLayer.sampleOrDefault(x, z, 0);
        if (classId == 0) {
            return y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
        }
        return surfaceBlockCache.computeIfAbsent(classId, id -> resolveSurfaceBlock(data, id, y));
    }

    private BlockState resolveSurfaceBlock(RuntimeData data, int classId, int y) {
        SurfaceGeologyClass surfaceClass = data.manifest.surfaceGeologyClasses.get(classId);
        if (surfaceClass == null || classId == 0) {
            return y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
        }
        OptionalBlock primary = blockState(surfaceClass.block());
        if (primary.state != null) {
            return primary.state;
        }
        OptionalBlock fallback = blockState(surfaceClass.fallbackBlock());
        if (fallback.state != null) {
            UkGeoMod.LOGGER.warn("Surface geology class {} uses fallback block {} because {} is missing", surfaceClass.name(), surfaceClass.fallbackBlock(), surfaceClass.block());
            return fallback.state;
        }
        UkGeoMod.LOGGER.warn("Surface geology class {} has no resolvable block; using stone", surfaceClass.name());
        return Blocks.STONE.defaultBlockState();
    }

    public int surfaceY(int x, int z) {
        RuntimeData data = data();
        if (data == null) {
            return fallbackHeight;
        }
        int decimetres = data.height.sampleDecimetresOrNodata(x, z);
        if (decimetres == R16HeightTileLayer.NODATA) {
            return waterFloorY(data, x, z, nodataSurfaceY);
        }
        int rawSurfaceY = rawSurfaceY(x, z, decimetres);
        return waterSmoothedSurfaceY(data, null, x, z, decimetres, rawSurfaceY);
    }

    int computeSurfaceY(RuntimeData data, HeightTileWindow heightWindow, int x, int z) {
        int decimetres = heightWindow.decimetresOrNodata(x, z);
        if (decimetres == HeightTileWindow.NODATA) {
            return waterFloorY(data, heightWindow, x, z, nodataSurfaceY);
        }
        int rawSurfaceY = rawSurfaceY(heightWindow, x, z, decimetres);
        return waterSmoothedSurfaceY(data, heightWindow, x, z, decimetres, rawSurfaceY);
    }

    private int rawSurfaceY(int x, int z, int decimetres) {
        return seaLevelY + Math.round((float) shapedHeightMetres(null, x, z, decimetres / 10.0));
    }

    private int rawSurfaceY(HeightTileWindow heightWindow, int x, int z, int decimetres) {
        return seaLevelY + Math.round((float) shapedHeightMetres(heightWindow, x, z, decimetres / 10.0));
    }

    private int waterSmoothedSurfaceY(RuntimeData data, HeightTileWindow heightWindow, int x, int z, int decimetres, int rawSurfaceY) {
        if (decimetres <= 0) {
            return waterFloorY(data, heightWindow, x, z, Math.min(rawSurfaceY, seaLevelY - SHALLOW_WATER_DEPTH));
        }
        int heightAboveSea = rawSurfaceY - seaLevelY;
        if (heightAboveSea <= 0 || heightAboveSea > WATER_EDGE_MAX_LAND_HEIGHT_ABOVE_SEA) {
            return rawSurfaceY;
        }
        Optional<WaterDistance> nearestWater = nearestHeightWater(heightWindow, x, z, WATER_EDGE_SMOOTHING_RADIUS);
        if (nearestWater.isEmpty()) {
            return rawSurfaceY;
        }
        double shoreWeight = 1.0 - smoothstep(nearestWater.get().blocks() / WATER_EDGE_SMOOTHING_RADIUS);
        double lowLandWeight = 1.0 - Math.clamp(heightAboveSea / (double) WATER_EDGE_MAX_LAND_HEIGHT_ABOVE_SEA, 0.0, 1.0);
        double amount = shoreWeight * lowLandWeight;
        int shoreSurfaceY = seaLevelY + 1;
        return Math.round((float) lerp(rawSurfaceY, shoreSurfaceY, amount));
    }

    private int waterFloorY(RuntimeData data, HeightTileWindow heightWindow, int x, int z, int deepFloorY) {
        Optional<LandDistance> nearestLand = nearestHeightLand(heightWindow, x, z, WATER_EDGE_SMOOTHING_RADIUS);
        if (nearestLand.isEmpty()) {
            return deepFloorY;
        }
        double deepWaterWeight = smoothstep(nearestLand.get().blocks() / WATER_EDGE_SMOOTHING_RADIUS);
        int shoreFloorY = seaLevelY - SHALLOW_WATER_DEPTH;
        return Math.round((float) lerp(shoreFloorY, deepFloorY, deepWaterWeight));
    }

    private int waterFloorY(RuntimeData data, int x, int z, int deepFloorY) {
        return waterFloorY(data, null, x, z, deepFloorY);
    }

    private Optional<WaterDistance> nearestHeightWater(HeightTileWindow heightWindow, int x, int z, int radius) {
        double bestDistanceSquared = Double.POSITIVE_INFINITY;
        int radiusSquared = radius * radius;
        int step = WATER_EDGE_SAMPLE_STEP;
        for (int dz = -radius; dz <= radius; dz += step) {
            for (int dx = -radius; dx <= radius; dx += step) {
                int distanceSquared = dx * dx + dz * dz;
                if (distanceSquared > radiusSquared) {
                    continue;
                }
                int sample = sampleDecimetresOrNodata(heightWindow, x + dx, z + dz);
                if (sample != R16HeightTileLayer.NODATA && sample > 0) {
                    continue;
                }
                if ((double) distanceSquared < bestDistanceSquared) {
                    bestDistanceSquared = distanceSquared;
                }
            }
        }
        return Double.isInfinite(bestDistanceSquared) ? Optional.empty() : Optional.of(new WaterDistance(Math.sqrt(bestDistanceSquared)));
    }

    private Optional<LandDistance> nearestHeightLand(HeightTileWindow heightWindow, int x, int z, int radius) {
        double bestDistanceSquared = Double.POSITIVE_INFINITY;
        int radiusSquared = radius * radius;
        int step = WATER_EDGE_SAMPLE_STEP;
        for (int dz = -radius; dz <= radius; dz += step) {
            for (int dx = -radius; dx <= radius; dx += step) {
                int distanceSquared = dx * dx + dz * dz;
                if (distanceSquared > radiusSquared) {
                    continue;
                }
                int sample = sampleDecimetresOrNodata(heightWindow, x + dx, z + dz);
                if (sample == R16HeightTileLayer.NODATA || sample <= 0) {
                    continue;
                }
                if ((double) distanceSquared < bestDistanceSquared) {
                    bestDistanceSquared = distanceSquared;
                }
            }
        }
        return Double.isInfinite(bestDistanceSquared) ? Optional.empty() : Optional.of(new LandDistance(Math.sqrt(bestDistanceSquared)));
    }

    private int sampleDecimetresOrNodata(HeightTileWindow heightWindow, int x, int z) {
        if (heightWindow != null) {
            return heightWindow.decimetresOrNodata(x, z);
        }
        RuntimeData data = data();
        return data == null ? R16HeightTileLayer.NODATA : data.height.sampleDecimetresOrNodata(x, z);
    }

    private double shapedHeightMetres(HeightTileWindow heightWindow, int x, int z, double metres) {
        double highlandWeight = smoothstep((metres - highlandStartMetres) / (highlandFullMetres - highlandStartMetres));
        if (highlandWeight > 0.0 && highlandSmoothingRadius > 0) {
            double smoothed = smoothedMetres(heightWindow, x, z, highlandSmoothingRadius).orElse(metres);
            metres = lerp(metres, smoothed, highlandWeight);
        }
        double lowlandWeight = metres <= 0.0 ? 1.0 : 1.0 - Math.clamp(metres / lowlandCeilingMetres, 0.0, 1.0);
        double scale = heightScale + lowlandExtraScale * lowlandWeight;
        scale = lerp(scale, highlandScale, highlandWeight);
        return metres * scale;
    }

    private Optional<Double> smoothedMetres(HeightTileWindow heightWindow, int x, int z, int radius) {
        double total = 0.0;
        int count = 0;
        int step = Math.max(1, radius / 2);
        for (int dz = -radius; dz <= radius; dz += step) {
            for (int dx = -radius; dx <= radius; dx += step) {
                int sample = sampleDecimetresOrNodata(heightWindow, x + dx, z + dz);
                if (sample != R16HeightTileLayer.NODATA) {
                    total += sample / 10.0;
                    count++;
                }
            }
        }
        return count == 0 ? Optional.empty() : Optional.of(total / count);
    }

    private OptionalInt smoothedSurfaceY(HeightTileWindow heightWindow, int x, int z, int radius) {
        int total = 0;
        int count = 0;
        int step = Math.max(1, radius / 2);
        for (int dz = -radius; dz <= radius; dz += step) {
            for (int dx = -radius; dx <= radius; dx += step) {
                int sample = sampleDecimetresOrNodata(heightWindow, x + dx, z + dz);
                if (sample != R16HeightTileLayer.NODATA) {
                    total += seaLevelY + Math.round((float) shapedHeightMetres(heightWindow, x + dx, z + dz, sample / 10.0));
                    count++;
                }
            }
        }
        return count == 0 ? OptionalInt.empty() : OptionalInt.of(Math.round((float) total / count));
    }

    private static double smoothstep(double value) {
        double t = Math.clamp(value, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double lerp(double start, double end, double amount) {
        return start + (end - start) * amount;
    }

    private static double normalOreAttempts(OreDefinition ore, int score) {
        if (score <= 0) {
            return ore.baseAttempts() + ore.maxBonusAttempts();
        }
        return ore.baseAttempts() + Math.round(ore.maxBonusAttempts() * (score / 255.0f));
    }

    private static int scaledOreAttempts(double normalAttempts, boolean inOreArea, java.util.Random random) {
        double multiplier = inOreArea ? ORE_AREA_ATTEMPT_MULTIPLIER : BACKGROUND_ORE_ATTEMPT_MULTIPLIER;
        double scaled = normalAttempts * multiplier;
        int wholeAttempts = (int) scaled;
        double fractionalAttempt = scaled - wholeAttempts;
        if (fractionalAttempt <= 0.0) {
            return wholeAttempts;
        }
        return wholeAttempts + (random.nextDouble() < fractionalAttempt ? 1 : 0);
    }

    private static boolean acceptOreHeight(OreDefinition ore, int y, int minY, int maxY, LevelHeightAccessor level, java.util.Random random) {
        double weight = oreHeightWeight(ore, y, minY, maxY, level);
        return weight > 0.0 && random.nextDouble() < weight;
    }

    private static double oreHeightWeight(OreDefinition ore, int y, int minY, int maxY, LevelHeightAccessor level) {
        return switch (ore.heightProfile()) {
            case UNIFORM -> y >= minY && y <= maxY ? 1.0 : 0.0;
            case TRIANGLE -> triangularHeightWeight(y, minY, scaledVanillaY(ore.vanillaPeakY(), level), maxY);
            case DEEP_BIASED -> deepOreWeight(y, minY, maxY);
            case TWO_PEAKS -> twoPeakHeightWeight(ore, y, minY, maxY, level);
        };
    }

    private static double triangularHeightWeight(int y, int minY, int peakY, int maxY) {
        if (y < minY || y > maxY) {
            return 0.0;
        }
        if (y == peakY) {
            return 1.0;
        }
        if (y < peakY) {
            return (double) (y - minY) / Math.max(1, peakY - minY);
        }
        return (double) (maxY - y) / Math.max(1, maxY - peakY);
    }

    private static double deepOreWeight(int y, int minY, int maxY) {
        if (y < minY || y > maxY) {
            return 0.0;
        }
        return 1.0 - (double) (y - minY) / Math.max(1, maxY - minY);
    }

    private static double twoPeakHeightWeight(OreDefinition ore, int y, int minY, int maxY, LevelHeightAccessor level) {
        int firstPeak = scaledVanillaY(ore.vanillaPeakY(), level);
        int secondPeak = scaledVanillaY(ore.vanillaSecondPeakY(), level);
        int valley = (firstPeak + secondPeak) >> 1;
        double lowPeak = triangularHeightWeight(y, minY, firstPeak, valley);
        double highPeak = triangularHeightWeight(y, valley, secondPeak, maxY);
        return Math.max(lowPeak, highPeak);
    }

    private static void logOreHeightProfile(OreDefinition ore, LevelHeightAccessor level, double normalAttempts, boolean inOreArea) {
        if (!DEBUG_ORE_HEIGHTS || !debuggedOreHeights.add(ore.name())) {
            return;
        }
        double multiplier = inOreArea ? ORE_AREA_ATTEMPT_MULTIPLIER : BACKGROUND_ORE_ATTEMPT_MULTIPLIER;
        int minY = scaledVanillaYFloor(ore.vanillaMinY(), level);
        int maxY = scaledVanillaYCeil(ore.vanillaMaxY(), level);
        int effectiveTerrainMaxY = effectiveOreTerrainMaxY(level);
        String peaks = switch (ore.heightProfile()) {
            case UNIFORM, DEEP_BIASED -> "-";
            case TRIANGLE -> "%s->%d".formatted(ore.vanillaPeakY(), scaledVanillaY(ore.vanillaPeakY(), level));
            case TWO_PEAKS -> "%s->%d, %s->%d".formatted(
                ore.vanillaPeakY(),
                scaledVanillaY(ore.vanillaPeakY(), level),
                ore.vanillaSecondPeakY(),
                scaledVanillaY(ore.vanillaSecondPeakY(), level)
            );
        };
        UkGeoMod.LOGGER.info(
            "Ore height profile ore={} vanillaRange={}..{} scaledRange={}..{} attemptsBeforeMultiplier={} effectiveAttempts={} profile={} peaks={} effectiveTerrainMaxY={} worldMinY={} worldMaxBuildY={} worldHeight={}",
            ore.name(),
            ore.vanillaMinY(),
            ore.vanillaMaxY(),
            minY,
            maxY,
            normalAttempts,
            normalAttempts * multiplier,
            ore.heightProfile(),
            peaks,
            effectiveTerrainMaxY,
            level.getMinBuildHeight(),
            level.getMaxBuildHeight(),
            level.getHeight()
        );
    }

    private static void logOrePlacementDebug(
        OreDefinition ore,
        LevelHeightAccessor level,
        int score,
        double normalAttempts,
        int attempts,
        int bandMin,
        int bandMax,
        int scaledPeakY,
        int skippedBandMisses,
        int acceptedCenters,
        int plannedPlacements,
        int minTerrainTop,
        int maxTerrainTop
    ) {
        if (!DEBUG_ORE_PLACEMENT || !"coal".equals(ore.name())) {
            return;
        }
        int effectiveTerrainMaxY = effectiveOreTerrainMaxY(level);
        String peak = scaledPeakY == Integer.MIN_VALUE ? "-" : Integer.toString(scaledPeakY);
        String terrainTopRange = attempts == 0 ? "-" : "%d..%d".formatted(minTerrainTop, maxTerrainTop);
        boolean intersectsAttemptedTerrain = attempts > 0 && bandMin <= maxTerrainTop - 1 && bandMax >= level.getMinBuildHeight() + 1;
        UkGeoMod.LOGGER.info(
            "Ore placement debug ore={} chunk={} score={} attemptsBeforeMultiplier={} attempts={} vanillaBand={}..{} vanillaPeak={} scaledBand={}..{} scaledPeak={} effectiveTerrainMaxY={} worldMinY={} worldMaxBuildY={} worldHeight={} attemptedTerrainTop={} bandIntersectsAttemptedTerrain={} skippedBandMisses={} acceptedCenters={} plannedPlacements={}",
            ore.name(),
            level instanceof ChunkAccess chunk ? chunk.getPos() : "?",
            score,
            normalAttempts,
            attempts,
            ore.vanillaMinY(),
            ore.vanillaMaxY(),
            ore.vanillaPeakY(),
            bandMin,
            bandMax,
            peak,
            effectiveTerrainMaxY,
            level.getMinBuildHeight(),
            level.getMaxBuildHeight(),
            level.getHeight(),
            terrainTopRange,
            intersectsAttemptedTerrain,
            skippedBandMisses,
            acceptedCenters,
            plannedPlacements
        );
    }

    private Optional<BlockStatePair> resolveOreBlocks(OreDefinition ore) {
        return blockStateCache.computeIfAbsent(ore.name(), ignored -> {
            OptionalBlock normal = blockState(ore.block());
            OptionalBlock deep = blockState(ore.deepslateBlock());
            if (normal.state == null || deep.state == null) {
                UkGeoMod.LOGGER.warn("Skipping geology layer {} because block {} or {} is missing", ore.name(), ore.block(), ore.deepslateBlock());
                return Optional.empty();
            }
            return Optional.of(new BlockStatePair(normal.state, deep.state));
        });
    }

    private OptionalBlock blockState(String id) {
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
        if (block == Blocks.AIR && !"minecraft:air".equals(id)) {
            return new OptionalBlock(null);
        }
        return new OptionalBlock(block.defaultBlockState());
    }

    private RuntimeData data() {
        RuntimeData current = runtimeData;
        if (current != null || attemptedDataLoad || !useConfigDataRoot) {
            return current;
        }
        synchronized (this) {
            if (runtimeData != null) {
                return runtimeData;
            }
            if (attemptedDataLoad) {
                return null;
            }
            attemptedDataLoad = true;
            Path root = UkGeoConfig.dataRoot(Path.of(".").toAbsolutePath().normalize());
            try {
                TileManifest manifest = TileManifest.load(root);
                Map<String, U8OreTileLayer> layers = new HashMap<>();
                for (Map.Entry<String, String> entry : manifest.orePaths.entrySet()) {
                    layers.put(entry.getKey(), new U8OreTileLayer(manifest, entry.getKey(), entry.getValue()));
                }
                U8OreTileLayer surfaceLayer = manifest.surfaceGeologyPath == null ? null : new U8OreTileLayer(manifest, "surface_geology", manifest.surfaceGeologyPath);
                U8OreTileLayer vegetationLayer = manifest.vegetationPath == null
                    ? null
                    : new U8OreTileLayer(manifest, "vegetation", manifest.vegetationPath, manifest.vegetationCellBlocks, manifest.paddedWidth, manifest.paddedDepth);
                U8OreTileLayer biomeRegionLayer = manifest.biomeRegionsPath == null
                    ? null
                    : new U8OreTileLayer(manifest, "biome_regions", manifest.biomeRegionsPath, manifest.biomeRegionsCellBlocks, manifest.paddedWidth, manifest.paddedDepth);
                U8OreTileLayer riverLayer = manifest.riversPath == null ? null : new U8OreTileLayer(manifest, "rivers", manifest.riversPath);
                U8OreTileLayer riverOrderLayer = manifest.riverOrderPath == null ? null : new U8OreTileLayer(manifest, "river_order", manifest.riverOrderPath);
                U8OreTileLayer riverHalfWidthLayer = manifest.riverHalfWidthPath == null ? null : new U8OreTileLayer(manifest, "river_half_width", manifest.riverHalfWidthPath);
                runtimeData = new RuntimeData(manifest, new R16HeightTileLayer(manifest), surfaceLayer, vegetationLayer, biomeRegionLayer, riverLayer, riverOrderLayer, riverHalfWidthLayer, layers, OreSettings.defaults());
                UkGeoMod.LOGGER.info("Loaded ukgeo manifest at {} with {}x{} tiles", root, manifest.tilesX(), manifest.tilesZ());
            } catch (IOException | RuntimeException ex) {
                UkGeoMod.LOGGER.warn("UK world data is missing or invalid at {}; using fallback terrain: {}", root, ex.getMessage());
                runtimeData = null;
            }
            return runtimeData;
        }
    }

    public String status() {
        RuntimeData data = data();
        if (data == null) {
            return "uk_world_data unavailable; fallback terrain active";
        }
        return "tiles=%dx%d tileSize=%d bounds=x %d..%d z %d..%d origin=%s heightScale=%.3f lowExtra=%.3f highScale=%.3f nodataY=%d riverRadius=%d riverDepth=%d vegetation=%s biomeRegions=%s heightCache=%s".formatted(
            data.manifest.tilesX(),
            data.manifest.tilesZ(),
            data.manifest.tileSize,
            data.manifest.minecraftMinX,
            data.manifest.minecraftMaxX,
            data.manifest.minecraftMinZ,
            data.manifest.minecraftMaxZ,
            data.manifest.originSummary(),
            heightScale,
            lowlandExtraScale,
            highlandScale,
            nodataSurfaceY,
            riverWidenRadius,
            riverCarveDepth,
            data.vegetationLayer == null ? "none" : "loaded",
            data.biomeRegionLayer == null ? "none" : "loaded",
            data.height.cacheStats()
        );
    }

    public Map<String, Integer> sampleOres(int x, int z) {
        RuntimeData data = data();
        Map<String, Integer> result = new HashMap<>();
        if (data == null) {
            return result;
        }
        for (Map.Entry<String, U8OreTileLayer> entry : data.oreLayers.entrySet()) {
            result.put(entry.getKey(), entry.getValue().sampleOrDefault(x, z, 0));
        }
        return result;
    }

    public String sampleSurface(int x, int z) {
        RuntimeData data = data();
        if (data == null || data.surfaceLayer == null) {
            return "none";
        }
        if (!hasHeightData(data, null, x, z)) {
            return "none (no height data)";
        }
        int classId = data.surfaceLayer.sampleOrDefault(x, z, 0);
        SurfaceGeologyClass surfaceClass = data.manifest.surfaceGeologyClasses.get(classId);
        return surfaceClass == null ? Integer.toString(classId) : surfaceClass.name() + "(" + classId + ")";
    }

    public String sampleHeightData(int x, int z) {
        RuntimeData data = data();
        if (data == null) {
            return "none";
        }
        int decimetres = data.height.sampleDecimetresOrNodata(x, z);
        if (decimetres == R16HeightTileLayer.NODATA) {
            return "NODATA / ocean / outside bounds";
        }
        return "%.1fm".formatted(decimetres / 10.0);
    }

    public String sampleVegetation(int x, int z) {
        RuntimeData data = data();
        if (data == null || data.vegetationLayer == null) {
            return "none";
        }
        if (!hasHeightData(data, null, x, z)) {
            return "none (no height data)";
        }
        int classId = data.vegetationLayer.sampleOrDefault(x, z, 0);
        VegetationClass vegetationClass = data.manifest.vegetationClasses.get(classId);
        return vegetationClass == null ? Integer.toString(classId) : vegetationClass.name() + "(" + classId + ")";
    }

    public String sampleBiomeRegion(int x, int z) {
        RuntimeData data = data();
        if (data == null || data.biomeRegionLayer == null) {
            return "none";
        }
        if (!hasHeightData(data, null, x, z)) {
            return "none (no height data)";
        }
        int classId = data.biomeRegionLayer.sampleOrDefault(x, z, 0);
        VegetationClass biomeRegionClass = data.manifest.biomeRegionClasses.get(classId);
        if (biomeRegionClass == null) {
            biomeRegionClass = data.manifest.vegetationClasses.get(classId);
        }
        return biomeRegionClass == null ? Integer.toString(classId) : biomeRegionClass.name() + "(" + classId + ")";
    }

    public int sampleRiver(int x, int z) {
        RuntimeData data = data();
        if (data == null || data.riverLayer == null) {
            return 0;
        }
        if (!hasHeightData(data, null, x, z)) {
            return 0;
        }
        return data.riverLayer.sampleOrDefault(x, z, 0);
    }

    public int sampleOilAmount(long seed, int x, int z) {
        RuntimeData data = data();
        if (data == null) {
            return 0;
        }
        return oilAmountForChunk(data, seed, new ChunkPos(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
    }

    private int vegetationClass(int x, int z) {
        RuntimeData data = data();
        if (data == null || data.vegetationLayer == null) {
            return 0;
        }
        return data.vegetationLayer.sampleOrDefault(x, z, 0);
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureManager, RandomState random, ChunkAccess chunk) {
        long startNanos = System.nanoTime();
        ChunkTerrainPlanner.Plan plan = chunkPlans.remove(chunk.getPos().toLong());
        if (plan != null) {
            decorationWaterPlans.put(chunk.getPos().toLong(), plan);
            trimChunkPlanMap(decorationWaterPlans, chunk.getPos());
            if (CLEAN_PLANNED_DELEGATE_FLUIDS) {
                long removeFluidsStartNanos = System.nanoTime();
                removeDelegateCaveFluids(chunk, plan, false);
                logTiming("removeDelegateCaveFluids(plan)", chunk.getPos(), removeFluidsStartNanos);
            }
            ChunkTerrainPlanner.applyOres(plan, chunk);
            long waterTicksStartNanos = System.nanoTime();
            scheduleWaterTicks(level, chunk, plan);
            logTiming("scheduleWaterTicks(plan)", chunk.getPos(), waterTicksStartNanos);
            long primeStartNanos = System.nanoTime();
            primeGenerationHeightmaps(chunk);
            logTiming("primeGenerationHeightmaps.beforeSurfaceFeatures", chunk.getPos(), primeStartNanos);
        } else {
            long removeFluidsStartNanos = System.nanoTime();
            removeDelegateCaveFluids(chunk);
            logTiming("removeDelegateCaveFluids(fallback)", chunk.getPos(), removeFluidsStartNanos);
            long primeStartNanos = System.nanoTime();
            primeGenerationHeightmaps(chunk);
            logTiming("primeGenerationHeightmaps.afterFallbackFluids", chunk.getPos(), primeStartNanos);
            long waterTicksStartNanos = System.nanoTime();
            scheduleWaterTicks(level, chunk);
            logTiming("scheduleWaterTicks(fallback)", chunk.getPos(), waterTicksStartNanos);
        }
        long snowPlaceStartNanos = System.nanoTime();
        placeHighAltitudeSnowAndIce(chunk);
        logTiming("placeHighAltitudeSnowAndIce", chunk.getPos(), snowPlaceStartNanos);
        long snowRemoveStartNanos = System.nanoTime();
        removeSnowAndIceBelowMinY(chunk);
        logTiming("removeSnowAndIceBelowMinY", chunk.getPos(), snowRemoveStartNanos);
        if (plan != null) {
            ChunkTerrainPlanner.enforceWaterColumns(plan, chunk);
        }
        long primeStartNanos = System.nanoTime();
        primeGenerationHeightmaps(chunk);
        logTiming("primeGenerationHeightmaps.finalBuildSurface", chunk.getPos(), primeStartNanos);
        long oilStartNanos = System.nanoTime();
        planCreateDieselGeneratorsOil(level.getLevel(), chunk.getPos());
        logTiming("planCreateDieselGeneratorsOil", chunk.getPos(), oilStartNanos);
        logTiming("buildSurface.total", chunk.getPos(), startNanos);
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
        long startNanos = System.nanoTime();
        ChunkPos chunkPos = chunk.getPos();

        logDecorationConfigOnce();

        /*
         * Full delegated biome decoration is enabled by default, because vanilla/modded biome features
         * and structure-related decoration should normally be present in UKGeo worlds. It can still be
         * disabled with -Dukgeo.disableBiomeFeatureDecoration=true if a datapack/mod feature causes a
         * generation stall. The entry/exit logging here is deliberately before and after the delegate call
         * so a 0% world-creation stall leaves a clear "entered full biome decoration" line in latest.log.
         */
        boolean ranFullBiomeDecoration = false;
        if (ENABLE_BIOME_FEATURE_DECORATION && !FULL_BIOME_DECORATION_RUNTIME_DISABLED.get()) {
            long biomeFeatureStartNanos = System.nanoTime();
            int active = ACTIVE_FULL_BIOME_DECORATIONS.incrementAndGet();
            if (DEBUG_BIOME_DECORATION) {
                UkGeoMod.LOGGER.info(
                    "UKGeo full biome decoration ENTER chunk={} thread={} active={} level={} disableFlag={} safeModdedPlants={}",
                    chunk.getPos(),
                    Thread.currentThread().getName(),
                    active,
                    level.getClass().getName(),
                    Boolean.getBoolean("ukgeo.disableBiomeFeatureDecoration"),
                    ENABLE_SAFE_MODDED_PLANTS
                );
            }
            try {
                super.applyBiomeDecoration(level, chunk, structureManager);
                ranFullBiomeDecoration = true;
            } catch (Throwable throwable) {
                UkGeoMod.LOGGER.error(
                    "UKGeo full biome decoration FAILED chunk={} thread={} active={} after={}ms",
                    chunk.getPos(),
                    Thread.currentThread().getName(),
                    active,
                    (System.nanoTime() - biomeFeatureStartNanos) / 1_000_000.0,
                    throwable
                );
                throw throwable;
            } finally {
                int remaining = ACTIVE_FULL_BIOME_DECORATIONS.decrementAndGet();
                long elapsedNanos = System.nanoTime() - biomeFeatureStartNanos;
                long elapsedMs = elapsedNanos / 1_000_000L;
                PERF.recordFullDecoration(elapsedNanos);
                if (elapsedMs >= SLOW_BIOME_DECORATION_WARN_MS) {
                    UkGeoMod.LOGGER.warn(
                        "UKGeo full biome decoration SLOW chunk={} elapsed={}ms thread={} remainingActive={} threshold={}ms",
                        chunk.getPos(), elapsedMs, Thread.currentThread().getName(), remaining, SLOW_BIOME_DECORATION_WARN_MS
                    );
                }
                if (FULL_BIOME_DECORATION_AUTO_DISABLE_MS > 0
                    && elapsedMs >= FULL_BIOME_DECORATION_AUTO_DISABLE_MS
                    && FULL_BIOME_DECORATION_RUNTIME_DISABLED.compareAndSet(false, true)) {
                    UkGeoMod.LOGGER.warn(
                        "UKGeo full biome decoration auto-disabled after slow chunk={} elapsed={}ms threshold={}ms. "
                            + "Safe local UKGeo flora and structures will continue; set -Dukgeo.fullBiomeDecorationAutoDisableMs=0 to disable this circuit breaker.",
                        chunk.getPos(), elapsedMs, FULL_BIOME_DECORATION_AUTO_DISABLE_MS
                    );
                } else if (DEBUG_BIOME_DECORATION) {
                    UkGeoMod.LOGGER.info(
                        "UKGeo full biome decoration EXIT chunk={} elapsed={}ms thread={} remainingActive={}",
                        chunk.getPos(), elapsedMs, Thread.currentThread().getName(), remaining
                    );
                }
            }
            logTiming("applyBiomeDecoration.biomeFeatures", chunk.getPos(), biomeFeatureStartNanos);
        } else if (DEBUG_BIOME_DECORATION) {
            UkGeoMod.LOGGER.info(
                "UKGeo full biome decoration SKIP chunk={} thread={} because -Dukgeo.disableBiomeFeatureDecoration=true",
                chunk.getPos(), Thread.currentThread().getName()
            );
        }
        if (ranFullBiomeDecoration) {
            long ancientCityCleanupStartNanos = System.nanoTime();
            cleanupBuriedAncientCityAir(chunk);
            long ancientElapsed = System.nanoTime() - ancientCityCleanupStartNanos;
            PERF.recordAncientCleanup(ancientElapsed);
            logTiming("cleanupBuriedAncientCityAir", chunk.getPos(), ancientCityCleanupStartNanos);
            long unsafeDecorationCleanupStartNanos = System.nanoTime();
            cleanupUnsafeDecoratedWaterAndRice(chunk, decorationWaterPlans.get(chunkPos.toLong()));
            long riceElapsed = System.nanoTime() - unsafeDecorationCleanupStartNanos;
            PERF.recordRiceCleanup(riceElapsed);
            logTiming("cleanupUnsafeDecoratedWaterAndRice", chunk.getPos(), unsafeDecorationCleanupStartNanos);
        }
        long snowRemoveStartNanos = System.nanoTime();
        removeSnowAndIceBelowMinY(chunk);
        logTiming("removeSnowAndIceBelowMinY.decoration", chunk.getPos(), snowRemoveStartNanos);
        ChunkTerrainPlanner.Plan plan = decorationWaterPlans.remove(chunkPos.toLong());
        if (plan != null) {
            ChunkTerrainPlanner.enforceWaterColumns(plan, chunk);
            long primeStartNanos = System.nanoTime();
            primeGenerationHeightmaps(chunk);
            logTiming("primeGenerationHeightmaps.decoration", chunk.getPos(), primeStartNanos);
            long vegetationStartNanos = System.nanoTime();
            placeVegetation(chunk, plan);
            logTiming("placeVegetation(plan.decoration)", chunk.getPos(), vegetationStartNanos);
        } else {
            long vegetationStartNanos = System.nanoTime();
            placeVegetation(chunk);
            logTiming("placeVegetation(fallback.decoration)", chunk.getPos(), vegetationStartNanos);
        }
        long elapsedNanos = System.nanoTime() - startNanos;
        PERF.recordApplyDecoration(elapsedNanos);
        logTiming("applyBiomeDecoration.total", chunk.getPos(), startNanos);
        logSlowTiming("applyBiomeDecoration.total", chunk.getPos(), elapsedNanos, SLOW_APPLY_BIOME_DECORATION_WARN_MS);
        PERF.maybeLog(chunk.getPos());
    }


    private void cleanupBuriedAncientCityAir(ChunkAccess chunk) {
        if (!ENABLE_ANCIENT_CITY_AIR_CLEANUP) {
            return;
        }
        if (!mayContainAncientCityCleanupTarget(chunk)) {
            return;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        ChunkPos pos = chunk.getPos();
        int minY = Math.max(chunk.getMinBuildHeight(), ANCIENT_CITY_AIR_CLEANUP_MIN_Y);
        int maxY = Math.min(chunk.getMaxBuildHeight() - 1, ANCIENT_CITY_AIR_CLEANUP_MAX_Y);
        if (minY > maxY) {
            return;
        }
        int cleared = 0;
        for (int localX = 0; localX < 16; localX++) {
            int worldX = pos.getMinBlockX() + localX;
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldZ = pos.getMinBlockZ() + localZ;
                for (int y = minY; y <= maxY; y++) {
                    BlockState state = chunk.getBlockState(cursor.set(localX, y, localZ));
                    if (!isAncientCityAnchor(state)) {
                        continue;
                    }
                    cleared += clearAncientCityPocket(chunk, cursor, localX, y, localZ, worldX, worldZ);
                }
            }
        }
        if (DEBUG_STRUCTURE_CLEANUP && cleared > 0) {
            UkGeoMod.LOGGER.info("UKGeo ancient city air cleanup chunk={} cleared={} blocks", chunk.getPos(), cleared);
        }
    }

    private static boolean mayContainAncientCityCleanupTarget(ChunkAccess chunk) {
        boolean[] found = {false};
        for (var section : chunk.getSections()) {
            section.getBiomes().getAll(biome -> {
                if (!found[0] && isAncientCityCandidateBiome(biome)) {
                    found[0] = true;
                }
            });
            if (found[0]) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAncientCityCandidateBiome(Holder<Biome> biome) {
        return biome.unwrapKey().map(key -> {
            ResourceLocation id = key.location();
            if (!"ukgeo".equals(id.getNamespace())) {
                return false;
            }
            String path = id.getPath();
            return path.equals("mountains") || path.equals("rocky") || path.contains("deep_dark");
        }).orElse(false);
    }

    private int clearAncientCityPocket(ChunkAccess chunk, BlockPos.MutableBlockPos cursor, int anchorX, int anchorY, int anchorZ, int worldX, int worldZ) {
        int cleared = 0;
        int minY = Math.max(chunk.getMinBuildHeight(), anchorY - 1);
        int maxY = Math.min(chunk.getMaxBuildHeight() - 1, anchorY + 5);
        for (int dx = -2; dx <= 2; dx++) {
            int localX = anchorX + dx;
            if (localX < 0 || localX > 15) {
                continue;
            }
            for (int dz = -2; dz <= 2; dz++) {
                int localZ = anchorZ + dz;
                if (localZ < 0 || localZ > 15) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    if (localX == anchorX && localZ == anchorZ && y == anchorY) {
                        continue;
                    }
                    cursor.set(localX, y, localZ);
                    BlockState state = chunk.getBlockState(cursor);
                    if (isAncientCityTerrainFill(state)) {
                        chunk.setBlockState(cursor, Blocks.AIR.defaultBlockState(), false);
                        cleared++;
                    }
                }
            }
        }
        return cleared;
    }

    private static boolean isAncientCityAnchor(BlockState state) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (key == null) {
            return false;
        }
        String path = key.getPath();
        return path.startsWith("sculk")
            || path.equals("reinforced_deepslate")
            || path.startsWith("deepslate_brick")
            || path.startsWith("deepslate_tile")
            || path.equals("soul_lantern")
            || path.endsWith("_wool");
    }

    private static boolean isAncientCityTerrainFill(BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty() || isAncientCityAnchor(state)) {
            return false;
        }
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (key == null) {
            return false;
        }
        String path = key.getPath();
        if (path.contains("brick") || path.contains("tile") || path.contains("lantern") || path.endsWith("_wool")) {
            return false;
        }
        return state.is(Blocks.STONE)
            || state.is(Blocks.DEEPSLATE)
            || state.is(Blocks.TUFF)
            || state.is(Blocks.CALCITE)
            || state.is(Blocks.GRANITE)
            || state.is(Blocks.DIORITE)
            || state.is(Blocks.ANDESITE)
            || state.is(Blocks.DIRT)
            || state.is(Blocks.GRAVEL)
            || path.contains("ore")
            || path.contains("stone")
            || path.contains("slate")
            || path.contains("tuff")
            || path.contains("rock")
            || path.contains("limestone")
            || path.contains("shale")
            || path.contains("gravel")
            || path.contains("dirt");
    }

    private void cleanupUnsafeDecoratedWaterAndRice(ChunkAccess chunk, ChunkTerrainPlanner.Plan plan) {
        if (!BuiltInRegistries.BLOCK.containsKey(FARMERS_DELIGHT_RICE_ID)) {
            return;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        Heightmap surfaceMap = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        ChunkPos pos = chunk.getPos();
        int maxY = chunk.getMaxBuildHeight() - 1;
        for (int localX = 0; localX < 16; localX++) {
            int worldX = pos.getMinBlockX() + localX;
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldZ = pos.getMinBlockZ() + localZ;
                int top = surfaceMap.getHighestTaken(localX, localZ);
                if (top <= seaLevelY || top >= maxY) {
                    continue;
                }
                RiverShape river = plan == null ? RiverShape.none(top) : plan.columns()[localZ * 16 + localX].river();
                if (river.hasWater()) {
                    continue;
                }
                int fromY = Math.max(chunk.getMinBuildHeight(), top - 1);
                int toY = Math.min(maxY, top + 4);
                boolean foundUnsafeRice = false;
                for (int y = fromY; y <= toY; y++) {
                    BlockState state = chunk.getBlockState(cursor.set(localX, y, localZ));
                    if (isFarmersDelightRice(state) && !hasAdjacentWater(chunk, cursor, localX, y - 1, localZ)) {
                        chunk.setBlockState(cursor, Blocks.AIR.defaultBlockState(), false);
                        foundUnsafeRice = true;
                    }
                }
                if (foundUnsafeRice) {
                    for (int y = fromY; y <= toY; y++) {
                        cursor.set(localX, y, localZ);
                        if (chunk.getBlockState(cursor).is(Blocks.WATER)) {
                            chunk.setBlockState(cursor, Blocks.AIR.defaultBlockState(), false);
                        }
                    }
                }
            }
        }
    }

    private static boolean isFarmersDelightRice(BlockState state) {
        return FARMERS_DELIGHT_RICE_ID.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    private Set<ResourceKey<PlacedFeature>> vanillaTreeFeaturesForChunk(ChunkAccess chunk) {
        /*
         * Keep decoration local to the chunk currently being decorated. The previous neighbour scan
         * called WorldGenLevel#getChunk for surrounding chunks from inside applyBiomeDecoration; during
         * parallel worldgen that can recursively request more chunks and make new-world creation stall.
         * Reading the current ChunkAccess sections is enough to discover this chunk's biome-style tree
         * feature set without causing extra chunk loads.
         */
        Set<ResourceKey<PlacedFeature>> discovered = vanillaTreeFeaturesInChunk(chunk);
        Set<ResourceKey<PlacedFeature>> ordered = new LinkedHashSet<>();
        for (ResourceKey<PlacedFeature> key : VANILLA_TREE_FEATURES) {
            if (discovered.contains(key)) {
                ordered.add(key);
            }
        }
        return ordered;
    }

    private Set<ResourceKey<PlacedFeature>> vanillaTreeFeaturesInChunk(ChunkAccess chunk) {
        long key = chunk.getPos().toLong();
        Set<ResourceKey<PlacedFeature>> cached = chunkTreeFeatureCache.get(key);
        if (cached != null) {
            return cached;
        }
        Set<ResourceKey<PlacedFeature>> discovered = new LinkedHashSet<>();
        for (var section : chunk.getSections()) {
            section.getBiomes().getAll(biome -> addVanillaTreeFeaturesForBiome(discovered, biome));
        }
        Set<ResourceKey<PlacedFeature>> immutable = Set.copyOf(discovered);
        Set<ResourceKey<PlacedFeature>> existing = chunkTreeFeatureCache.putIfAbsent(key, immutable);
        return existing == null ? immutable : existing;
    }

    private static void addVanillaTreeFeaturesForBiome(Set<ResourceKey<PlacedFeature>> features, Holder<Biome> biome) {
        biome.unwrapKey().ifPresent(key -> {
            ResourceLocation id = key.location();
            String path = id.getPath();
            switch (path) {
                case "plains", "arable", "improved_grassland", "urban" -> features.add(VegetationPlacements.TREES_PLAINS);
                case "forest" -> features.add(VegetationPlacements.TREES_BIRCH_AND_OAK);
                case "broadleaf_woodland" -> {
                    features.add(VegetationPlacements.TREES_BIRCH_AND_OAK);
                    features.add(VegetationPlacements.TREES_SPARSE_JUNGLE);
                }
                case "taiga", "conifer_woodland" -> features.add(VegetationPlacements.TREES_TAIGA);
                case "meadow", "neutral_grassland" -> features.add(VegetationPlacements.TREES_MEADOW);
                case "flower_forest", "calcareous_grassland" -> features.add(VegetationPlacements.TREES_FLOWER_FOREST);
                case "windswept_hills", "acid_grassland" -> features.add(VegetationPlacements.TREES_WINDSWEPT_HILLS);
                case "swamp" -> features.add(VegetationPlacements.TREES_SWAMP);
                case "wetland" -> {
                    features.add(VegetationPlacements.TREES_SWAMP);
                    features.add(VegetationPlacements.TREES_SPARSE_JUNGLE);
                }
                case "windswept_savanna", "heath" -> features.add(VegetationPlacements.TREES_WINDSWEPT_SAVANNA);
                case "mountains" -> features.add(VegetationPlacements.TREES_WINDSWEPT_HILLS);
                default -> {
                }
            }
        });
    }

    private static int scaleVanillaY(int vanillaY, LevelHeightAccessor level) {
        return scaledVanillaY(vanillaY, level);
    }

    private static int scaledVanillaY(double vanillaY, LevelHeightAccessor level) {
        return clampBuildY((int) Math.round(scaledVanillaYRaw(vanillaY, level)), level);
    }

    private static int scaledVanillaYFloor(double vanillaY, LevelHeightAccessor level) {
        return clampBuildY((int) Math.floor(scaledVanillaYRaw(vanillaY, level)), level);
    }

    private static int scaledVanillaYCeil(double vanillaY, LevelHeightAccessor level) {
        return clampBuildY((int) Math.ceil(scaledVanillaYRaw(vanillaY, level)), level);
    }

    private static double scaledVanillaYRaw(double vanillaY, LevelHeightAccessor level) {
        int effectiveMinY = level.getMinBuildHeight();
        int effectiveMaxY = effectiveOreTerrainMaxY(level);
        double scale = (double) (effectiveMaxY - effectiveMinY) / (VANILLA_MAX_Y - VANILLA_MIN_Y);
        return effectiveMinY + (vanillaY - VANILLA_MIN_Y) * scale;
    }

    private static int effectiveOreTerrainMaxY(LevelHeightAccessor level) {
        return Math.clamp(DEFAULT_EFFECTIVE_ORE_TERRAIN_MAX_Y, level.getMinBuildHeight() + 1, level.getMaxBuildHeight() - 1);
    }

    private static int clampBuildY(int y, LevelHeightAccessor level) {
        return Math.clamp(y, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
    }

    private void placeVegetation(ChunkAccess chunk, ChunkTerrainPlanner.Plan plan) {
        long startNanos = DEBUG_FLORA_TIMINGS ? System.nanoTime() : 0L;
        int candidateCount = 0;
        int placedCount = 0;
        int skippedCount = 0;
        RuntimeData data = data();
        if (data == null || data.vegetationLayer == null) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minBuildY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight() - 1;
        Heightmap surfaceMap = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        for (int localX = 0; localX < 16; localX++) {
            int worldX = pos.getMinBlockX() + localX;
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldZ = pos.getMinBlockZ() + localZ;
                ChunkTerrainPlanner.ColumnPlan column = plan.columns()[localZ * 16 + localX];
                if (!column.hasHeightData()) {
                    continue;
                }
                int vegetationClass = column.vegetationClass();
                if (vegetationClass == 0 || vegetationClass == VEGETATION_FRESHWATER) {
                    continue;
                }
                double baseDensity = floraBaseDensity(vegetationClass);
                if (baseDensity <= 0.0) {
                    continue;
                }
                double patch = (valueNoise(worldX, worldZ, 0.045, 0x464c4f5241504154L) + 1.0) * 0.5;
                double cluster = patch < FLORA_CLUSTER_THRESHOLD ? 0.0 : smoothstep((patch - FLORA_CLUSTER_THRESHOLD) / (1.0 - FLORA_CLUSTER_THRESHOLD));
                double clusterRoll = hashUnit(worldX, worldZ, pos.toLong() ^ 0x464c4f52414d4943L);
                double ambientRoll = hashUnit(worldX, worldZ, pos.toLong() ^ 0x414d42464c4f5241L);
                if (!shouldEvaluateFloraColumn(vegetationClass, baseDensity, cluster, clusterRoll, ambientRoll)) {
                    continue;
                }
                candidateCount++;
                int top = surfaceMap.getHighestTaken(localX, localZ);
                if (top < minBuildY + 1 || top >= maxY) {
                    skippedCount++;
                    continue;
                }
                UkGeoChunkGenerator.RiverShape river = column.river();
                if (river.hasWater() || top <= seaLevelY || river.terrainSurfaceY() != top) {
                    skippedCount++;
                    continue;
                }
                if (column.steep() || column.coastalBeach()) {
                    skippedCount++;
                    continue;
                }
                BlockState ground = chunk.getBlockState(cursor.set(localX, top, localZ));
                if (!isFloraGround(ground)) {
                    skippedCount++;
                    continue;
                }
                int y = top + 1;
                if (y >= maxY || !chunk.getBlockState(cursor.set(localX, y, localZ)).isAir()) {
                    skippedCount++;
                    continue;
                }
                if (placePlannedGroundFlora(chunk, cursor, pos, vegetationClass, localX, y, localZ, worldX, worldZ, baseDensity, cluster, clusterRoll, ambientRoll)) {
                    placedCount++;
                } else {
                    skippedCount++;
                }
            }
        }
        if (DEBUG_FLORA_TIMINGS) {
            UkGeoMod.LOGGER.info(
                "UKGeo flora timings chunk={} mode=planned elapsed={}ms candidates={} placed={} skipped={}",
                chunk.getPos(),
                (System.nanoTime() - startNanos) / 1_000_000.0,
                candidateCount,
                placedCount,
                skippedCount
            );
        }
    }

    private void placeVegetation(ChunkAccess chunk) {
        long startNanos = DEBUG_FLORA_TIMINGS ? System.nanoTime() : 0L;
        int candidateCount = 0;
        int placedCount = 0;
        int skippedCount = 0;
        RuntimeData data = data();
        if (data == null || data.vegetationLayer == null) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        long seed = (((long) pos.x) << 32) ^ (pos.z & 0xffffffffL) ^ 0x564547554b47454fL;
        java.util.Random random = new java.util.Random(seed);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minBuildY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight() - 1;
        Heightmap surfaceMap = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        RuntimeData runtime = data;
        int cellBlocks = data.manifest.vegetationCellBlocks;
        for (int localX = 0; localX < 16; localX++) {
            int worldX = pos.getMinBlockX() + localX;
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldZ = pos.getMinBlockZ() + localZ;
                if (Math.floorMod(worldX, cellBlocks) != 0 || Math.floorMod(worldZ, cellBlocks) != 0) {
                    continue;
                }
                if (!hasHeightData(data, null, worldX, worldZ)) {
                    continue;
                }
                int vegetationClass = data.vegetationLayer.sampleOrDefault(worldX, worldZ, 0);
                if (vegetationClass == VEGETATION_URBAN || vegetationClass == VEGETATION_FRESHWATER) {
                    continue;
                }
                if (hashUnit(worldX, worldZ, pos.toLong() ^ 0x46414c4c4241434bL) > legacyVegetationCandidateChance(vegetationClass)) {
                    continue;
                }
                candidateCount++;
                int top = surfaceMap.getHighestTaken(localX, localZ);
                if (top < minBuildY + 1 || top >= maxY) {
                    skippedCount++;
                    continue;
                }
                RiverShape river = runtime == null ? RiverShape.none(top) : computeRiverShape(runtime, null, worldX, worldZ, top, minBuildY);
                if (river.hasWater() || top <= seaLevelY || river.terrainSurfaceY() != top) {
                    skippedCount++;
                    continue;
                }
                BlockState ground = chunk.getBlockState(cursor.set(localX, top, localZ));
                if (!ground.is(Blocks.GRASS_BLOCK) && !ground.is(Blocks.DIRT)) {
                    skippedCount++;
                    continue;
                }
                int y = top + 1;
                if (y >= maxY || !chunk.getBlockState(cursor.set(localX, y, localZ)).isAir()) {
                    skippedCount++;
                    continue;
                }
                if (placeVegetationForClass(chunk, cursor, random, vegetationClass, localX, y, localZ)) {
                    placedCount++;
                } else {
                    skippedCount++;
                }
            }
        }
        if (DEBUG_FLORA_TIMINGS) {
            UkGeoMod.LOGGER.info(
                "UKGeo flora timings chunk={} mode=fallback elapsed={}ms candidates={} placed={} skipped={}",
                chunk.getPos(),
                (System.nanoTime() - startNanos) / 1_000_000.0,
                candidateCount,
                placedCount,
                skippedCount
            );
        }
    }

    private void placeHighAltitudeSnowAndIce(ChunkAccess chunk) {
        ChunkPos pos = chunk.getPos();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        Heightmap surfaceMap = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        int maxY = chunk.getMaxBuildHeight() - 1;
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int top = surfaceMap.getHighestTaken(localX, localZ);
                if (top < SNOW_ICE_MIN_Y) {
                    continue;
                }
                BlockState surface = chunk.getBlockState(cursor.set(localX, top, localZ));
                if (surface.is(Blocks.WATER) && top + 1 < maxY && chunk.getBlockState(cursor.set(localX, top + 1, localZ)).isAir()) {
                    chunk.setBlockState(cursor, Blocks.ICE.defaultBlockState(), false);
                    continue;
                }
                if ((surface.is(Blocks.GRASS_BLOCK) || surface.is(Blocks.DIRT) || surface.is(Blocks.STONE))
                    && top + 1 < maxY
                    && chunk.getBlockState(cursor.set(localX, top + 1, localZ)).isAir()) {
                    chunk.setBlockState(cursor, Blocks.SNOW.defaultBlockState(), false);
                }
            }
        }
    }

    private static void removeSnowAndIceBelowMinY(ChunkAccess chunk) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        Heightmap surfaceMap = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        int minY = chunk.getMinBuildHeight();
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int top = surfaceMap.getHighestTaken(localX, localZ);
                if (top >= SNOW_ICE_MIN_Y) {
                    continue;
                }
                int fromY = Math.max(minY, top - 3);
                int toY = Math.min(SNOW_ICE_MIN_Y - 1, top + 3);
                for (int y = fromY; y <= toY; y++) {
                    BlockState state = chunk.getBlockState(cursor.set(localX, y, localZ));
                    if (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.ICE) || state.is(Blocks.FROSTED_ICE) || state.is(Blocks.POWDER_SNOW)) {
                        chunk.setBlockState(cursor, Blocks.AIR.defaultBlockState(), false);
                    }
                }
            }
        }
    }

    private static void primeGenerationHeightmaps(ChunkAccess chunk) {
        Heightmap.primeHeightmaps(
            chunk,
            EnumSet.of(
                Heightmap.Types.WORLD_SURFACE_WG,
                Heightmap.Types.OCEAN_FLOOR_WG,
                Heightmap.Types.MOTION_BLOCKING,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES
            )
        );
    }

    private static void removeDelegateCaveFluids(ChunkAccess chunk, ChunkTerrainPlanner.Plan plan, boolean removeLava) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                ChunkTerrainPlanner.ColumnPlan column = plan.columns()[localZ * 16 + localX];
                removeDelegateCaveFluids(chunk, cursor, localX, localZ, column.terrainTop(), removeLava, column, plan.seaLevelY());
            }
        }
    }

    private void removeDelegateCaveFluids(ChunkAccess chunk) {
        RuntimeData runtime = data();
        if (runtime == null) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minBuildY = chunk.getMinBuildHeight();
        for (int localZ = 0; localZ < 16; localZ++) {
            int worldZ = pos.getMinBlockZ() + localZ;
            for (int localX = 0; localX < 16; localX++) {
                int worldX = pos.getMinBlockX() + localX;
                int surface = surfaceY(worldX, worldZ);
                boolean hasHeightData = hasHeightData(runtime, null, worldX, worldZ);
                int vegetationClass = hasHeightData ? sampleVegetationClass(runtime, worldX, worldZ) : 0;
                RiverShape river = hasHeightData ? computeSurfaceWaterShape(runtime, null, worldX, worldZ, surface, minBuildY, vegetationClass) : RiverShape.none(surface);
                removeDelegateCaveFluids(chunk, cursor, localX, localZ, river.terrainSurfaceY(), true, river, surface, seaLevelY);
            }
        }
    }

    private static void removeDelegateCaveFluids(
        ChunkAccess chunk,
        BlockPos.MutableBlockPos cursor,
        int localX,
        int localZ,
        int terrainTop,
        boolean removeLava
    ) {
        removeDelegateCaveFluids(chunk, cursor, localX, localZ, terrainTop, removeLava, (ChunkTerrainPlanner.ColumnPlan) null, 0);
    }

    private static void removeDelegateCaveFluids(
        ChunkAccess chunk,
        BlockPos.MutableBlockPos cursor,
        int localX,
        int localZ,
        int terrainTop,
        boolean removeLava,
        ChunkTerrainPlanner.ColumnPlan column,
        int seaLevelY
    ) {
        // Delegate/carver fluids below the custom terrain surface are not final world fluids.
        // Surface oceans and rivers are placed by columnStateFor above terrainTop.
        int scanTop = delegateFluidScanTop(chunk, terrainTop);
        for (int y = chunk.getMinBuildHeight(); y <= scanTop; y++) {
            BlockState state = chunk.getBlockState(cursor.set(localX, y, localZ));
            if (state.is(Blocks.WATER) || (removeLava && state.is(Blocks.LAVA))) {
                if (column != null && isProtectedWaterCave(column, y, seaLevelY)) {
                    continue;
                }
                chunk.setBlockState(cursor, Blocks.AIR.defaultBlockState(), false);
            }
        }
    }

    private static void removeDelegateCaveFluids(
        ChunkAccess chunk,
        BlockPos.MutableBlockPos cursor,
        int localX,
        int localZ,
        int terrainTop,
        boolean removeLava,
        RiverShape river,
        int originalSurfaceY,
        int seaLevelY
    ) {
        int scanTop = delegateFluidScanTop(chunk, terrainTop);
        for (int y = chunk.getMinBuildHeight(); y <= scanTop; y++) {
            BlockState state = chunk.getBlockState(cursor.set(localX, y, localZ));
            if (state.is(Blocks.WATER) || (removeLava && state.is(Blocks.LAVA))) {
                if (isProtectedWaterCave(river, originalSurfaceY, terrainTop, y, seaLevelY)) {
                    continue;
                }
                chunk.setBlockState(cursor, Blocks.AIR.defaultBlockState(), false);
            }
        }
    }

    private static int delegateFluidScanTop(ChunkAccess chunk, int terrainTop) {
        return Math.min(Math.min(terrainTop, VANILLA_MAX_Y), chunk.getMaxBuildHeight() - 1);
    }

    private static boolean isProtectedWaterCave(ChunkTerrainPlanner.ColumnPlan column, int y, int seaLevelY) {
        return isProtectedWaterCave(column.river(), column.originalSurfaceY(), column.terrainTop(), y, seaLevelY);
    }

    private static boolean isProtectedWaterCave(RiverShape river, int originalSurfaceY, int terrainTop, int y, int seaLevelY) {
        int waterSurfaceY;
        if (river.hasWater()) {
            waterSurfaceY = river.waterSurfaceY();
        } else if (originalSurfaceY < seaLevelY) {
            waterSurfaceY = seaLevelY;
        } else {
            return false;
        }
        return y <= waterSurfaceY && y >= terrainTop - WATERBED_PROTECTION_DEPTH;
    }

    private boolean placePlannedGroundFlora(
        ChunkAccess chunk,
        BlockPos.MutableBlockPos cursor,
        ChunkPos chunkPos,
        int vegetationClass,
        int localX,
        int y,
        int localZ,
        int worldX,
        int worldZ,
        double baseDensity,
        double cluster,
        double clusterRoll,
        double ambientRoll
    ) {
        if (cluster <= 0.0) {
            return placeAmbientGroundFlora(chunk, cursor, chunkPos, vegetationClass, localX, y, localZ, worldX, worldZ, ambientRoll);
        }
        double density = Math.clamp(baseDensity * FLORA_DENSITY_MULTIPLIER * lerp(0.35, 1.0, cluster) * FLORA_CLUSTER_FILL_MULTIPLIER, 0.0, 0.48);
        if (clusterRoll > density) {
            return placeAmbientGroundFlora(chunk, cursor, chunkPos, vegetationClass, localX, y, localZ, worldX, worldZ, ambientRoll);
        }
        double choice = hashUnit(worldX, worldZ, chunkPos.toLong() ^ 0x464c4f524143484fL);
        BlockState plant = plannedGroundFloraState(vegetationClass, worldX, worldZ, choice);
        if (plant == null) {
            return false;
        }
        double tallChance = tallFloraChance(vegetationClass, cluster);
        if (isTallGrassClass(vegetationClass) && choice < tallChance) {
            return placeDoublePlant(chunk, cursor, localX, y, localZ, Blocks.TALL_GRASS.defaultBlockState());
        }
        if (isFernClass(vegetationClass) && choice < tallChance) {
            return placeDoublePlant(chunk, cursor, localX, y, localZ, Blocks.LARGE_FERN.defaultBlockState());
        }
        return placePlant(chunk, cursor, localX, y, localZ, plant);
    }

    private boolean placeAmbientGroundFlora(
        ChunkAccess chunk,
        BlockPos.MutableBlockPos cursor,
        ChunkPos chunkPos,
        int vegetationClass,
        int localX,
        int y,
        int localZ,
        int worldX,
        int worldZ,
        double ambientRoll
    ) {
        double ambientChance = AMBIENT_FLORA_CHANCE * ambientFloraMultiplier(vegetationClass);
        if (ambientRoll > ambientChance) {
            return false;
        }
        double choice = hashUnit(worldX, worldZ, chunkPos.toLong() ^ 0x414d4243484f4943L);
        if (isFernClass(vegetationClass) && choice < AMBIENT_FERN_CHANCE) {
            return placePlant(chunk, cursor, localX, y, localZ, Blocks.FERN.defaultBlockState());
        }
        if (isTallGrassClass(vegetationClass) && choice < AMBIENT_FERN_CHANCE + AMBIENT_TALL_GRASS_CHANCE) {
            return placeDoublePlant(chunk, cursor, localX, y, localZ, Blocks.TALL_GRASS.defaultBlockState());
        }
        if (choice > 1.0 - AMBIENT_FLOWER_CHANCE) {
            return placePlant(chunk, cursor, localX, y, localZ, flowerForClass(vegetationClass, worldX, worldZ));
        }
        return placePlant(chunk, cursor, localX, y, localZ, Blocks.SHORT_GRASS.defaultBlockState());
    }

    private static boolean shouldEvaluateFloraColumn(int vegetationClass, double baseDensity, double cluster, double clusterRoll, double ambientRoll) {
        double ambientChance = AMBIENT_FLORA_CHANCE * ambientFloraMultiplier(vegetationClass);
        if (ambientRoll <= ambientChance) {
            return true;
        }
        if (cluster <= 0.0) {
            return false;
        }
        double density = Math.clamp(baseDensity * FLORA_DENSITY_MULTIPLIER * lerp(0.35, 1.0, cluster) * FLORA_CLUSTER_FILL_MULTIPLIER, 0.0, 0.48);
        return clusterRoll <= density;
    }

    private static double legacyVegetationCandidateChance(int vegetationClass) {
        return switch (vegetationClass) {
            case VEGETATION_BROADLEAF_WOODLAND, VEGETATION_CONIFER_WOODLAND, VEGETATION_WETLAND -> 0.28;
            case VEGETATION_IMPROVED_GRASSLAND, VEGETATION_NEUTRAL_GRASSLAND, VEGETATION_CALCAREOUS_GRASSLAND -> 0.30;
            case VEGETATION_ACID_GRASSLAND, VEGETATION_HEATH -> 0.24;
            case VEGETATION_ARABLE -> 0.08;
            case VEGETATION_ROCKY -> 0.12;
            default -> 0.0;
        };
    }

    private static double ambientFloraMultiplier(int vegetationClass) {
        return switch (vegetationClass) {
            case VEGETATION_BROADLEAF_WOODLAND, VEGETATION_CONIFER_WOODLAND -> 1.10;
            case VEGETATION_IMPROVED_GRASSLAND, VEGETATION_NEUTRAL_GRASSLAND -> 1.20;
            case VEGETATION_CALCAREOUS_GRASSLAND -> 1.00;
            case VEGETATION_ACID_GRASSLAND, VEGETATION_WETLAND -> 0.95;
            case VEGETATION_HEATH -> 0.70;
            case VEGETATION_ARABLE -> 0.45;
            case VEGETATION_URBAN -> 0.65;
            case VEGETATION_ROCKY -> 0.35;
            default -> 0.0;
        };
    }

    private static double floraBaseDensity(int vegetationClass) {
        return switch (vegetationClass) {
            case VEGETATION_BROADLEAF_WOODLAND -> 0.34;
            case VEGETATION_CONIFER_WOODLAND -> 0.32;
            case VEGETATION_ARABLE -> 0.08;
            case VEGETATION_IMPROVED_GRASSLAND -> 0.48;
            case VEGETATION_NEUTRAL_GRASSLAND -> 0.46;
            case VEGETATION_CALCAREOUS_GRASSLAND -> 0.42;
            case VEGETATION_ACID_GRASSLAND -> 0.34;
            case VEGETATION_WETLAND -> 0.38;
            case VEGETATION_HEATH -> 0.30;
            case VEGETATION_URBAN -> 0.22;
            case VEGETATION_ROCKY -> 0.14;
            default -> 0.0;
        };
    }

    private static double tallFloraChance(int vegetationClass, double cluster) {
        double patchBoost = cluster > 0.45 ? 0.08 : 0.0;
        double baseChance = switch (vegetationClass) {
            case VEGETATION_BROADLEAF_WOODLAND -> 0.08 + patchBoost;
            case VEGETATION_CONIFER_WOODLAND -> 0.13 + patchBoost;
            case VEGETATION_IMPROVED_GRASSLAND, VEGETATION_NEUTRAL_GRASSLAND -> 0.10 + patchBoost;
            case VEGETATION_CALCAREOUS_GRASSLAND -> 0.06 + patchBoost * 0.5;
            case VEGETATION_ACID_GRASSLAND, VEGETATION_WETLAND -> 0.12 + patchBoost;
            case VEGETATION_HEATH -> 0.05;
            case VEGETATION_URBAN -> 0.04;
            case VEGETATION_ROCKY -> 0.03;
            default -> 0.0;
        };
        return baseChance * TALL_FLORA_CHANCE_MULTIPLIER;
    }

    private static boolean isTallGrassClass(int vegetationClass) {
        return vegetationClass == VEGETATION_IMPROVED_GRASSLAND
            || vegetationClass == VEGETATION_NEUTRAL_GRASSLAND
            || vegetationClass == VEGETATION_CALCAREOUS_GRASSLAND
            || vegetationClass == VEGETATION_URBAN;
    }

    private static boolean isFernClass(int vegetationClass) {
        return vegetationClass == VEGETATION_BROADLEAF_WOODLAND
            || vegetationClass == VEGETATION_CONIFER_WOODLAND
            || vegetationClass == VEGETATION_ACID_GRASSLAND
            || vegetationClass == VEGETATION_WETLAND;
    }

    private BlockState plannedGroundFloraState(int vegetationClass, int worldX, int worldZ, double choice) {
        BlockState moddedPlant = moddedPlantForClass(vegetationClass, worldX, worldZ);
        if (moddedPlant != null) {
            return moddedPlant;
        }
        double flowerPatch = (valueNoise(worldX, worldZ, 0.028, 0x464c4f5745525041L) + 1.0) * 0.5;
        double flowerChance = switch (vegetationClass) {
            case VEGETATION_CALCAREOUS_GRASSLAND -> 0.28;
            case VEGETATION_NEUTRAL_GRASSLAND -> 0.20;
            case VEGETATION_IMPROVED_GRASSLAND -> 0.12;
            case VEGETATION_ARABLE -> 0.16;
            case VEGETATION_URBAN -> 0.08;
            case VEGETATION_HEATH -> 0.04;
            case VEGETATION_ROCKY -> 0.03;
            default -> 0.08;
        };
        flowerChance *= FLOWER_CHANCE_MULTIPLIER * lerp(0.20, 1.15, flowerPatch);
        double flowerRoll = hashUnit(worldX, worldZ, 0x464c4f5745524f4cL);
        if (flowerRoll < flowerChance) {
            return flowerForClass(vegetationClass, worldX, worldZ);
        }
        return switch (vegetationClass) {
            case VEGETATION_BROADLEAF_WOODLAND -> choice < 0.30 * FERN_CHANCE_MULTIPLIER ? Blocks.FERN.defaultBlockState() : Blocks.SHORT_GRASS.defaultBlockState();
            case VEGETATION_CONIFER_WOODLAND -> choice < 0.72 * FERN_CHANCE_MULTIPLIER ? Blocks.FERN.defaultBlockState() : Blocks.SHORT_GRASS.defaultBlockState();
            case VEGETATION_ACID_GRASSLAND -> choice < 0.45 * FERN_CHANCE_MULTIPLIER ? Blocks.FERN.defaultBlockState() : Blocks.SHORT_GRASS.defaultBlockState();
            case VEGETATION_WETLAND -> choice < 0.50 * FERN_CHANCE_MULTIPLIER ? Blocks.FERN.defaultBlockState() : Blocks.SHORT_GRASS.defaultBlockState();
            case VEGETATION_HEATH -> choice < 0.22 * FERN_CHANCE_MULTIPLIER ? Blocks.FERN.defaultBlockState() : Blocks.SHORT_GRASS.defaultBlockState();
            case VEGETATION_ROCKY -> choice < 0.18 * FERN_CHANCE_MULTIPLIER ? Blocks.FERN.defaultBlockState() : Blocks.SHORT_GRASS.defaultBlockState();
            default -> Blocks.SHORT_GRASS.defaultBlockState();
        };
    }

    private static BlockState flowerForClass(int vegetationClass, int worldX, int worldZ) {
        int variant = (int) Math.floor(hashUnit(worldX, worldZ, 0x464c4f5745524944L) * 8.0);
        return switch (vegetationClass) {
            case VEGETATION_CALCAREOUS_GRASSLAND -> switch (variant % 5) {
                case 0 -> Blocks.OXEYE_DAISY.defaultBlockState();
                case 1 -> Blocks.AZURE_BLUET.defaultBlockState();
                case 2 -> Blocks.CORNFLOWER.defaultBlockState();
                case 3 -> Blocks.WHITE_TULIP.defaultBlockState();
                default -> Blocks.DANDELION.defaultBlockState();
            };
            case VEGETATION_NEUTRAL_GRASSLAND, VEGETATION_IMPROVED_GRASSLAND, VEGETATION_URBAN -> switch (variant % 8) {
                case 0 -> Blocks.POPPY.defaultBlockState();
                case 1 -> Blocks.DANDELION.defaultBlockState();
                case 2 -> Blocks.ALLIUM.defaultBlockState();
                case 3 -> Blocks.OXEYE_DAISY.defaultBlockState();
                case 4 -> Blocks.CORNFLOWER.defaultBlockState();
                case 5 -> Blocks.PINK_TULIP.defaultBlockState();
                case 6 -> Blocks.RED_TULIP.defaultBlockState();
                default -> Blocks.ORANGE_TULIP.defaultBlockState();
            };
            case VEGETATION_WETLAND -> variant % 3 == 0 ? Blocks.BLUE_ORCHID.defaultBlockState() : Blocks.DANDELION.defaultBlockState();
            default -> variant % 2 == 0 ? Blocks.DANDELION.defaultBlockState() : Blocks.POPPY.defaultBlockState();
        };
    }

    private BlockState moddedPlantForClass(int vegetationClass, int worldX, int worldZ) {
        if (!ENABLE_SAFE_MODDED_PLANTS) {
            return null;
        }
        double roll = hashUnit(worldX, worldZ, 0x4d4f44444544504cL);
        double chance = switch (vegetationClass) {
            case VEGETATION_ARABLE -> 0.18;
            case VEGETATION_IMPROVED_GRASSLAND, VEGETATION_NEUTRAL_GRASSLAND -> 0.08;
            case VEGETATION_CALCAREOUS_GRASSLAND -> 0.07;
            case VEGETATION_ACID_GRASSLAND, VEGETATION_HEATH -> 0.09;
            case VEGETATION_WETLAND -> 0.12;
            case VEGETATION_BROADLEAF_WOODLAND, VEGETATION_CONIFER_WOODLAND -> 0.06;
            default -> 0.0;
        };
        if (roll >= chance) {
            return null;
        }

        String[] candidates = switch (vegetationClass) {
            case VEGETATION_ARABLE -> MODDED_ARABLE_PLANTS;
            case VEGETATION_IMPROVED_GRASSLAND, VEGETATION_NEUTRAL_GRASSLAND, VEGETATION_CALCAREOUS_GRASSLAND -> MODDED_GRASSLAND_PLANTS;
            case VEGETATION_ACID_GRASSLAND, VEGETATION_HEATH -> MODDED_HEATH_PLANTS;
            case VEGETATION_WETLAND -> MODDED_WETLAND_PLANTS;
            case VEGETATION_BROADLEAF_WOODLAND -> MODDED_BROADLEAF_PLANTS;
            case VEGETATION_CONIFER_WOODLAND -> MODDED_CONIFER_PLANTS;
            default -> null;
        };
        if (candidates == null || candidates.length == 0) {
            return null;
        }
        int start = (int) Math.floor(hashUnit(worldX, worldZ, 0x4d4f4446494e4458L) * candidates.length);
        for (int i = 0; i < candidates.length; i++) {
            String id = candidates[(start + i) % candidates.length];
            BlockState state = optionalPlantState(id);
            if (state != null) {
                return state;
            }
        }
        return null;
    }

    private BlockState optionalPlantState(String id) {
        return optionalPlantCache.computeIfAbsent(id, this::blockState).state();
    }

    private static boolean isFloraGround(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT);
    }

    private boolean placeVegetationForClass(ChunkAccess chunk, BlockPos.MutableBlockPos cursor, java.util.Random random, int vegetationClass, int localX, int y, int localZ) {
        switch (vegetationClass) {
            case VEGETATION_BROADLEAF_WOODLAND -> {
                if (random.nextInt(6) == 0) {
                    return placePlant(chunk, cursor, localX, y, localZ, randomGrassOrFlower(random, vegetationClass));
                }
            }
            case VEGETATION_CONIFER_WOODLAND -> {
                if (random.nextInt(5) == 0) {
                    if (random.nextInt(5) == 0) {
                        return placeDoublePlant(chunk, cursor, localX, y, localZ, Blocks.LARGE_FERN.defaultBlockState());
                    } else {
                        return placePlant(chunk, cursor, localX, y, localZ, Blocks.FERN.defaultBlockState());
                    }
                }
            }
            case VEGETATION_WETLAND -> {
                if (hasAdjacentWater(chunk, cursor, localX, y - 1, localZ) && random.nextInt(3) == 0) {
                    return placeSugarCane(chunk, cursor, random, localX, y, localZ);
                } else if (hasAdjacentWater(chunk, cursor, localX, y - 1, localZ) && random.nextInt(8) == 0) {
                    return placeLilyPadNearWater(chunk, cursor, localX, y - 1, localZ);
                } else if (random.nextInt(4) == 0) {
                    return placePlant(chunk, cursor, localX, y, localZ, randomWetlandPlant(random));
                } else if (random.nextInt(12) == 0) {
                    return placeDoublePlant(chunk, cursor, localX, y, localZ, Blocks.LARGE_FERN.defaultBlockState());
                }
            }
            case VEGETATION_HEATH -> {
                if (random.nextInt(5) == 0) {
                    return placePlant(chunk, cursor, localX, y, localZ, random.nextInt(3) == 0 ? Blocks.DEAD_BUSH.defaultBlockState() : Blocks.FERN.defaultBlockState());
                }
            }
            case VEGETATION_ARABLE -> {
                if (random.nextInt(18) == 0) {
                    return placePlant(chunk, cursor, localX, y, localZ, randomGrassOrFlower(random, vegetationClass));
                }
            }
            case VEGETATION_IMPROVED_GRASSLAND -> {
                if (random.nextInt(5) == 0) {
                    return placePlant(chunk, cursor, localX, y, localZ, randomGrassOrFlower(random, vegetationClass));
                } else if (random.nextInt(35) == 0) {
                    return placeDoublePlant(chunk, cursor, localX, y, localZ, Blocks.TALL_GRASS.defaultBlockState());
                }
            }
            case VEGETATION_NEUTRAL_GRASSLAND, VEGETATION_CALCAREOUS_GRASSLAND -> {
                if (random.nextInt(4) == 0) {
                    return placePlant(chunk, cursor, localX, y, localZ, randomGrassOrFlower(random, vegetationClass));
                } else if (random.nextInt(30) == 0) {
                    return placeDoublePlant(chunk, cursor, localX, y, localZ, Blocks.TALL_GRASS.defaultBlockState());
                }
            }
            case VEGETATION_ACID_GRASSLAND -> {
                if (random.nextInt(5) == 0) {
                    return placePlant(chunk, cursor, localX, y, localZ, random.nextBoolean() ? Blocks.FERN.defaultBlockState() : Blocks.SHORT_GRASS.defaultBlockState());
                } else if (random.nextInt(45) == 0) {
                    return placeDoublePlant(chunk, cursor, localX, y, localZ, Blocks.LARGE_FERN.defaultBlockState());
                }
            }
            case 0, VEGETATION_ROCKY -> {
                if (random.nextInt(10) == 0) {
                    return placePlant(chunk, cursor, localX, y, localZ, Blocks.SHORT_GRASS.defaultBlockState());
                }
            }
            default -> {
            }
        }
        return false;
    }

    private static boolean placePlant(ChunkAccess chunk, BlockPos.MutableBlockPos cursor, int localX, int y, int localZ, BlockState state) {
        if (y < chunk.getMaxBuildHeight() && chunk.getBlockState(cursor.set(localX, y, localZ)).isAir()) {
            chunk.setBlockState(cursor, state, false);
            return true;
        }
        return false;
    }

    private static boolean placeDoublePlant(ChunkAccess chunk, BlockPos.MutableBlockPos cursor, int localX, int y, int localZ, BlockState state) {
        if (y + 1 >= chunk.getMaxBuildHeight()) {
            return false;
        }
        if (!chunk.getBlockState(cursor.set(localX, y, localZ)).isAir() || !chunk.getBlockState(cursor.set(localX, y + 1, localZ)).isAir()) {
            return false;
        }
        chunk.setBlockState(cursor.set(localX, y, localZ), state.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER), false);
        chunk.setBlockState(cursor.set(localX, y + 1, localZ), state.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER), false);
        return true;
    }

    private static boolean placeSugarCane(ChunkAccess chunk, BlockPos.MutableBlockPos cursor, java.util.Random random, int localX, int y, int localZ) {
        int height = 1 + random.nextInt(3);
        for (int dy = 0; dy < height; dy++) {
            int py = y + dy;
            if (py >= chunk.getMaxBuildHeight() || !chunk.getBlockState(cursor.set(localX, py, localZ)).isAir()) {
                return false;
            }
        }
        BlockState state = Blocks.SUGAR_CANE.defaultBlockState();
        for (int dy = 0; dy < height; dy++) {
            chunk.setBlockState(cursor.set(localX, y + dy, localZ), state, false);
        }
        return true;
    }

    private static boolean placeLilyPadNearWater(ChunkAccess chunk, BlockPos.MutableBlockPos cursor, int localX, int waterY, int localZ) {
        int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] offset : offsets) {
            int px = localX + offset[0];
            int pz = localZ + offset[1];
            int py = waterY + 1;
            if (px < 0 || px > 15 || pz < 0 || pz > 15 || py >= chunk.getMaxBuildHeight()) {
                continue;
            }
            if (chunk.getBlockState(cursor.set(px, waterY, pz)).is(Blocks.WATER) && chunk.getBlockState(cursor.set(px, py, pz)).isAir()) {
                chunk.setBlockState(cursor, Blocks.LILY_PAD.defaultBlockState(), false);
                return true;
            }
        }
        return false;
    }

    private static BlockState randomGrassOrFlower(java.util.Random random, int vegetationClass) {
        if (random.nextInt(5) != 0) {
            return Blocks.SHORT_GRASS.defaultBlockState();
        }
        return switch (vegetationClass) {
            case VEGETATION_CALCAREOUS_GRASSLAND -> switch (random.nextInt(4)) {
                case 0 -> Blocks.OXEYE_DAISY.defaultBlockState();
                case 1 -> Blocks.AZURE_BLUET.defaultBlockState();
                case 2 -> Blocks.CORNFLOWER.defaultBlockState();
                default -> Blocks.DANDELION.defaultBlockState();
            };
            case VEGETATION_NEUTRAL_GRASSLAND -> switch (random.nextInt(5)) {
                case 0 -> Blocks.POPPY.defaultBlockState();
                case 1 -> Blocks.DANDELION.defaultBlockState();
                case 2 -> Blocks.ALLIUM.defaultBlockState();
                case 3 -> Blocks.OXEYE_DAISY.defaultBlockState();
                default -> Blocks.CORNFLOWER.defaultBlockState();
            };
            default -> random.nextBoolean() ? Blocks.DANDELION.defaultBlockState() : Blocks.POPPY.defaultBlockState();
        };
    }

    private static BlockState randomWetlandPlant(java.util.Random random) {
        return switch (random.nextInt(5)) {
            case 0 -> Blocks.BLUE_ORCHID.defaultBlockState();
            case 1 -> Blocks.FERN.defaultBlockState();
            default -> Blocks.SHORT_GRASS.defaultBlockState();
        };
    }

    private static boolean hasAdjacentWater(ChunkAccess chunk, BlockPos.MutableBlockPos cursor, int localX, int y, int localZ) {
        if (y < chunk.getMinBuildHeight() || y >= chunk.getMaxBuildHeight()) {
            return false;
        }
        int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] offset : offsets) {
            int px = localX + offset[0];
            int pz = localZ + offset[1];
            if (px < 0 || px > 15 || pz < 0 || pz > 15) {
                continue;
            }
            if (chunk.getBlockState(cursor.set(px, y, pz)).is(Blocks.WATER)) {
                return true;
            }
        }
        return false;
    }

    private void scheduleWaterTicks(WorldGenRegion level, ChunkAccess chunk, ChunkTerrainPlanner.Plan plan) {
        ChunkPos chunkPos = chunk.getPos();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int waterTickDelay = Fluids.WATER.getTickDelay(level);
        for (int localX = 0; localX < 16; localX++) {
            int worldX = chunkPos.getMinBlockX() + localX;
            for (int localZ = 0; localZ < 16; localZ++) {
                ChunkTerrainPlanner.ColumnPlan column = plan.columns()[localZ * 16 + localX];
                UkGeoChunkGenerator.RiverShape river = column.river();
                if (river.hasWater()) {
                    scheduleWaterColumn(level, chunk, cursor, worldX, chunkPos.getMinBlockZ() + localZ, river.terrainSurfaceY() + 1, Math.max(river.waterSurfaceY(), seaLevelY), waterTickDelay);
                } else if (column.originalSurfaceY() < seaLevelY) {
                    scheduleWaterColumn(level, chunk, cursor, worldX, chunkPos.getMinBlockZ() + localZ, column.originalSurfaceY() + 1, seaLevelY, waterTickDelay);
                }
            }
        }
    }

    private void scheduleWaterTicks(WorldGenRegion level, ChunkAccess chunk) {
        ChunkPos chunkPos = chunk.getPos();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minBuildY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight() - 1;
        int waterTickDelay = Fluids.WATER.getTickDelay(level);
        for (int localX = 0; localX < 16; localX++) {
            int worldX = chunkPos.getMinBlockX() + localX;
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldZ = chunkPos.getMinBlockZ() + localZ;
                int top = Math.clamp(surfaceY(worldX, worldZ), minBuildY + 1, maxY);
                RuntimeData runtime = data();
                boolean hasHeightData = runtime != null && hasHeightData(runtime, null, worldX, worldZ);
                int vegetationClass = hasHeightData ? sampleVegetationClass(runtime, worldX, worldZ) : 0;
                RiverShape river = hasHeightData ? computeSurfaceWaterShape(runtime, null, worldX, worldZ, top, minBuildY, vegetationClass) : RiverShape.none(top);
                if (river.hasWater()) {
                    scheduleWaterColumn(level, chunk, cursor, worldX, worldZ, river.terrainSurfaceY() + 1, Math.max(river.waterSurfaceY(), seaLevelY), waterTickDelay);
                } else if (top < seaLevelY) {
                    scheduleWaterColumn(level, chunk, cursor, worldX, worldZ, top + 1, seaLevelY, waterTickDelay);
                }
            }
        }
    }

    private static void scheduleWaterColumn(WorldGenRegion level, ChunkAccess chunk, BlockPos.MutableBlockPos cursor, int worldX, int worldZ, int minY, int maxY, int tickDelay) {
        int fromY = Math.max(minY, chunk.getMinBuildHeight());
        int toY = Math.min(maxY, chunk.getMaxBuildHeight() - 1);
        if (!SCHEDULE_FULL_WATER_COLUMNS) {
            if (fromY <= toY) {
                cursor.set(worldX, toY, worldZ);
                if (chunk.getBlockState(cursor).getFluidState().is(Fluids.WATER)) {
                    level.scheduleTick(cursor.immutable(), Fluids.WATER, tickDelay);
                }
            }
            return;
        }
        for (int y = fromY; y <= toY; y++) {
            cursor.set(worldX, y, worldZ);
            if (chunk.getBlockState(cursor).getFluidState().is(Fluids.WATER)) {
                level.scheduleTick(cursor.immutable(), Fluids.WATER, tickDelay);
            }
        }
    }

    private void planCreateDieselGeneratorsOil(ServerLevel level, ChunkPos chunkPos) {
        if (!ENABLE_CREATE_DIESEL_OIL_INTEGRATION) {
            return;
        }
        RuntimeData data = data();
        if (data == null) {
            return;
        }
        int amount = oilAmountForChunk(data, level.getSeed(), chunkPos);
        UkGeoOilIntegration.enqueue(level, chunkPos, amount);
        if (DEBUG_OIL_GEN) {
            UkGeoMod.LOGGER.info("UKGeo oil planned chunk={} amount={}mB", chunkPos, amount);
        }
    }

    private int oilAmountForChunk(RuntimeData data, long seed, ChunkPos chunkPos) {
        int score = oilScoreForChunk(data, chunkPos);
        if (score < OIL_SCORE_THRESHOLD) {
            return 0;
        }
        double richness = (score - OIL_SCORE_THRESHOLD) / (double) (255 - OIL_SCORE_THRESHOLD);
        long mixedSeed = seed ^ (((long) chunkPos.x) << 32) ^ (chunkPos.z & 0xffffffffL) ^ 0x4f494c554b47454fL;
        java.util.Random random = new java.util.Random(mixedSeed);
        double variation = 0.85 + random.nextDouble() * 0.3;
        int amount = (int) Math.round(lerp(OIL_DEPOSIT_MIN_MILLIBUCKETS, OIL_DEPOSIT_MAX_MILLIBUCKETS, richness) * variation);
        return Math.clamp(amount, OIL_DEPOSIT_MIN_MILLIBUCKETS, OIL_DEPOSIT_MAX_MILLIBUCKETS);
    }

    private int oilScoreForChunk(RuntimeData data, ChunkPos chunkPos) {
        int score = 0;
        for (int localZ : OIL_SAMPLE_OFFSETS) {
            int z = chunkPos.getMinBlockZ() + localZ;
            for (int localX : OIL_SAMPLE_OFFSETS) {
                int x = chunkPos.getMinBlockX() + localX;
                score = Math.max(score, oilScoreAt(data, x, z));
            }
        }
        return score;
    }

    private int oilScoreAt(RuntimeData data, int x, int z) {
        int score = Math.max(oreLayerScore(data, "limestone", x, z), oreLayerScore(data, "calcite", x, z));
        return Math.max(score, surfaceOilScore(data, x, z));
    }

    private static int oreLayerScore(RuntimeData data, String layerName, int x, int z) {
        U8OreTileLayer layer = data.oreLayers.get(layerName);
        return layer == null ? 0 : layer.sampleOrDefault(x, z, 0);
    }

    private static int surfaceOilScore(RuntimeData data, int x, int z) {
        if (data.surfaceLayer == null) {
            return 0;
        }
        int classId = data.surfaceLayer.sampleOrDefault(x, z, 0);
        SurfaceGeologyClass surfaceClass = data.manifest.surfaceGeologyClasses.get(classId);
        if (surfaceClass == null) {
            return 0;
        }
        return isOilBearingSurface(surfaceClass) ? 220 : 0;
    }

    private static boolean isOilBearingSurface(SurfaceGeologyClass surfaceClass) {
        return isOilBearingText(surfaceClass.name())
            || isOilBearingText(surfaceClass.block())
            || isOilBearingText(surfaceClass.fallbackBlock());
    }

    private static boolean isOilBearingText(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("limestone")
            || lower.contains("calcite")
            || lower.contains("chalk")
            || lower.contains("dolomite")
            || lower.contains("dolostone")
            || lower.contains("calcareous");
    }


    private static void logDecorationConfigOnce() {
        if (DECORATION_CONFIG_LOGGED.compareAndSet(false, true)) {
            UkGeoMod.LOGGER.warn(
                "UKGeo biome feature decoration config: fullDecorationEnabled={} disableFlag={} debugDecoration={} slowWarnMs={} safeModdedPlants={}. "
                    + "Disable full delegated biome features with -Dukgeo.disableBiomeFeatureDecoration=true if world creation stalls at 0%. "
                    + "Auto-disable slow delegated decoration with ukgeo.fullBiomeDecorationAutoDisableMs={}ms.",
                ENABLE_BIOME_FEATURE_DECORATION,
                Boolean.getBoolean("ukgeo.disableBiomeFeatureDecoration"),
                DEBUG_BIOME_DECORATION,
                SLOW_BIOME_DECORATION_WARN_MS,
                ENABLE_SAFE_MODDED_PLANTS,
                FULL_BIOME_DECORATION_AUTO_DISABLE_MS
            );
        }
    }

    private static void logTiming(String label, ChunkPos chunkPos, long startNanos) {
        if (DEBUG_GEN_TIMINGS) {
            UkGeoMod.LOGGER.info("UKGeo timing chunk {} {}={}ms", chunkPos, label, (System.nanoTime() - startNanos) / 1_000_000.0);
        }
    }

    private static void logSlowTiming(String label, ChunkPos chunkPos, long elapsedNanos, long thresholdMs) {
        if (thresholdMs > 0L && elapsedNanos >= thresholdMs * 1_000_000L) {
            UkGeoMod.LOGGER.warn("UKGeo slow operation chunk {} {}={}ms threshold={}ms", chunkPos, label, elapsedNanos / 1_000_000.0, thresholdMs);
        }
    }

    private static void trimChunkPlanMap(ConcurrentHashMap<Long, ChunkTerrainPlanner.Plan> plans, ChunkPos center) {
        if (plans.size() <= MAX_PENDING_CHUNK_PLANS) {
            return;
        }
        int keepRadius = 96;
        for (Long key : plans.keySet()) {
            int x = ChunkPos.getX(key);
            int z = ChunkPos.getZ(key);
            if (Math.abs(x - center.x) > keepRadius || Math.abs(z - center.z) > keepRadius) {
                plans.remove(key);
                if (plans.size() <= MAX_PENDING_CHUNK_PLANS) {
                    return;
                }
            }
        }
    }

    private static final class BoundedCache<K, V> {
        private final int maxEntries;
        private final LinkedHashMap<K, V> values;

        private BoundedCache(int maxEntries) {
            this.maxEntries = Math.max(16, maxEntries);
            this.values = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                    return size() > BoundedCache.this.maxEntries;
                }
            };
        }

        synchronized V get(K key) {
            return values.get(key);
        }

        synchronized V putIfAbsent(K key, V value) {
            V existing = values.get(key);
            if (existing != null) {
                return existing;
            }
            values.put(key, value);
            return null;
        }
    }

    private static final class PerfCounters {
        private final LongAdder chunksFilled = new LongAdder();
        private final LongAdder fillNanos = new LongAdder();
        private final LongAdder maxFillNanos = new LongAdder();
        private final LongAdder chunksDecorated = new LongAdder();
        private final LongAdder decorationNanos = new LongAdder();
        private final LongAdder maxDecorationNanos = new LongAdder();
        private final LongAdder fullDecorationCalls = new LongAdder();
        private final LongAdder fullDecorationNanos = new LongAdder();
        private final LongAdder maxFullDecorationNanos = new LongAdder();
        private final LongAdder ancientCleanupCalls = new LongAdder();
        private final LongAdder ancientCleanupNanos = new LongAdder();
        private final LongAdder riceCleanupCalls = new LongAdder();
        private final LongAdder riceCleanupNanos = new LongAdder();

        void recordFill(long nanos) {
            chunksFilled.increment();
            fillNanos.add(nanos);
            addMax(maxFillNanos, nanos);
        }

        void recordApplyDecoration(long nanos) {
            chunksDecorated.increment();
            decorationNanos.add(nanos);
            addMax(maxDecorationNanos, nanos);
        }

        void recordFullDecoration(long nanos) {
            fullDecorationCalls.increment();
            fullDecorationNanos.add(nanos);
            addMax(maxFullDecorationNanos, nanos);
        }

        void recordAncientCleanup(long nanos) {
            ancientCleanupCalls.increment();
            ancientCleanupNanos.add(nanos);
        }

        void recordRiceCleanup(long nanos) {
            riceCleanupCalls.increment();
            riceCleanupNanos.add(nanos);
        }

        void maybeLog(ChunkPos chunkPos) {
            if (!DEBUG_GEN_TIMINGS && !DEBUG_BIOME_DECORATION && !DEBUG_STRUCTURE_CLEANUP) {
                return;
            }
            long decorated = chunksDecorated.sum();
            if (decorated <= 0 || decorated % Math.max(1, PERF_LOG_INTERVAL_CHUNKS) != 0) {
                return;
            }
            long filled = chunksFilled.sum();
            long fullCalls = fullDecorationCalls.sum();
            UkGeoMod.LOGGER.info(
                "UKGeo perf chunk={} filled={} avgFill={}ms maxFill={}ms decorated={} avgDecor={}ms maxDecor={}ms fullDecorCalls={} avgFullDecor={}ms maxFullDecor={}ms ancientCleanupCalls={} ancientCleanupMs={} riceCleanupCalls={} riceCleanupMs={} fullDecorAutoDisabled={}",
                chunkPos,
                filled,
                avgMillis(fillNanos.sum(), filled),
                millis(maxFillNanos.sum()),
                decorated,
                avgMillis(decorationNanos.sum(), decorated),
                millis(maxDecorationNanos.sum()),
                fullCalls,
                avgMillis(fullDecorationNanos.sum(), fullCalls),
                millis(maxFullDecorationNanos.sum()),
                ancientCleanupCalls.sum(),
                millis(ancientCleanupNanos.sum()),
                riceCleanupCalls.sum(),
                millis(riceCleanupNanos.sum()),
                FULL_BIOME_DECORATION_RUNTIME_DISABLED.get()
            );
        }

        private static void addMax(LongAdder adder, long value) {
            synchronized (adder) {
                long current = adder.sum();
                if (value > current) {
                    adder.add(value - current);
                }
            }
        }

        private static double avgMillis(long nanos, long count) {
            return count <= 0 ? 0.0 : millis(nanos / count);
        }

        private static double millis(long nanos) {
            return nanos / 1_000_000.0;
        }
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
        ChunkPos chunkPos = level.getCenter();
        Holder<Biome> biome = level.getBiome(chunkPos.getWorldPosition().atY(level.getMaxBuildHeight() - 1));
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
        random.setDecorationSeed(level.getSeed(), chunkPos.getMinBlockX(), chunkPos.getMinBlockZ());
        NaturalSpawner.spawnMobsForChunkGeneration(level, biome, chunkPos, random);
    }

    @Override
    public void applyCarvers(
        WorldGenRegion level,
        long seed,
        RandomState random,
        BiomeManager biomeManager,
        StructureManager structureManager,
        ChunkAccess chunk,
        GenerationStep.Carving carving
    ) {
        if (ENABLE_VANILLA_CARVERS) {
            if (DEBUG_CAVES) {
                UkGeoMod.LOGGER.info(
                    "UKGeo cave debug applying vanilla delegate carvers chunk={} step={} chunkY={}..{} preserveDelegateNoiseCaves={} deepCarversEnabled={}",
                    chunk.getPos(),
                    carving,
                    chunk.getMinBuildHeight(),
                    chunk.getMaxBuildHeight() - 1,
                    PRESERVE_DELEGATE_NOISE_CAVES,
                    ENABLE_DEEP_CARVERS
                );
            }
            caveDelegate.ifPresent(delegate -> delegate.applyCarvers(level, seed, random, biomeManager, structureManager, chunk, carving));
        } else if (DEBUG_CAVES) {
            UkGeoMod.LOGGER.info("UKGeo cave debug skipped vanilla delegate carvers chunk={} step={}", chunk.getPos(), carving);
        }
        if (ENABLE_DEEP_CARVERS && carving == GenerationStep.Carving.AIR) {
            applyDeepCaves(seed, chunk);
        }
    }

    private static void applyDeepCaves(long seed, ChunkAccess chunk) {
        int minY = Math.max(chunk.getMinBuildHeight() + DEEP_CAVE_BOTTOM_MARGIN, DEEP_CAVE_MIN_Y);
        int maxY = Math.min(chunk.getMaxBuildHeight() - 1, DEEP_CAVE_MAX_Y);
        if (minY > maxY) {
            return;
        }
        ChunkPos chunkPos = chunk.getPos();
        long startNanos = DEBUG_GEN_TIMINGS ? System.nanoTime() : 0L;
        for (int dz = -DEEP_CAVE_ORIGIN_CHUNK_RADIUS; dz <= DEEP_CAVE_ORIGIN_CHUNK_RADIUS; dz++) {
            for (int dx = -DEEP_CAVE_ORIGIN_CHUNK_RADIUS; dx <= DEEP_CAVE_ORIGIN_CHUNK_RADIUS; dx++) {
                carveDeepCavesFromOrigin(seed, chunk, chunkPos.x + dx, chunkPos.z + dz, minY, maxY);
            }
        }
        if (DEBUG_CAVES) {
            UkGeoMod.LOGGER.info(
                "UKGeo cave debug applied bounded deep carvers chunk={} range={}..{} delegateNoisePreserved={}",
                chunkPos,
                minY,
                maxY,
                PRESERVE_DELEGATE_NOISE_CAVES
            );
        }
        logTiming("applyDeepCaves", chunkPos, startNanos);
    }

    private static void carveDeepCavesFromOrigin(long seed, ChunkAccess targetChunk, int originChunkX, int originChunkZ, int minY, int maxY) {
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(deepCaveSeed(seed, originChunkX, originChunkZ)));
        if (random.nextDouble() > 0.24) {
            return;
        }
        int tunnelCount = 1 + (random.nextDouble() < 0.18 ? 1 : 0);
        for (int tunnel = 0; tunnel < tunnelCount; tunnel++) {
            double x = originChunkX * 16.0 + random.nextDouble() * 16.0;
            double y = minY + random.nextDouble() * Math.max(1, maxY - minY + 1);
            double z = originChunkZ * 16.0 + random.nextDouble() * 16.0;
            double yaw = random.nextDouble() * Math.PI * 2.0;
            double pitch = (random.nextDouble() - 0.5) * 0.34;
            int length = 32 + random.nextInt(36);
            double baseRadius = 1.35 + random.nextDouble() * 1.65;
            for (int step = 0; step < length; step++) {
                double progress = (double) step / Math.max(1, length - 1);
                double taper = Math.sin(Math.PI * progress);
                double wobble = Math.sin(progress * Math.PI * 3.0 + random.nextDouble() * 0.25) * 0.25;
                double horizontalRadius = Math.max(0.75, baseRadius * (0.65 + taper * 0.75) + wobble);
                double verticalRadius = Math.max(0.55, horizontalRadius * (0.48 + random.nextDouble() * 0.16));
                carveDeepCaveEllipsoid(targetChunk, x, y, z, horizontalRadius, verticalRadius, minY, maxY);
                yaw += (random.nextDouble() - 0.5) * 0.22;
                pitch = pitch * 0.72 + (random.nextDouble() - 0.5) * 0.12;
                pitch = clamp(pitch, -0.45, 0.45);
                x += Math.cos(yaw) * Math.cos(pitch) * 1.6;
                z += Math.sin(yaw) * Math.cos(pitch) * 1.6;
                y += Math.sin(pitch) * 1.1;
                if (y < minY + 1 || y > maxY - 1) {
                    pitch = -pitch * 0.65;
                    y = clamp(y, minY + 1, maxY - 1);
                }
            }
        }
    }

    private static long deepCaveSeed(long seed, int chunkX, int chunkZ) {
        long hash = seed ^ 0x6a09e667f3bcc909L;
        hash ^= (long) chunkX * 0xbf58476d1ce4e5b9L;
        hash ^= (long) chunkZ * 0x94d049bb133111ebL;
        hash ^= hash >>> 30;
        hash *= 0xbf58476d1ce4e5b9L;
        hash ^= hash >>> 27;
        hash *= 0x94d049bb133111ebL;
        return hash ^ (hash >>> 31);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void carveDeepCaveEllipsoid(
        ChunkAccess chunk,
        double centerX,
        double centerY,
        double centerZ,
        double horizontalRadius,
        double verticalRadius,
        int minY,
        int maxY
    ) {
        ChunkPos chunkPos = chunk.getPos();
        int chunkMinX = chunkPos.getMinBlockX();
        int chunkMinZ = chunkPos.getMinBlockZ();
        int minWorldX = Math.max(chunkMinX, (int) Math.floor(centerX - horizontalRadius - 1.0));
        int maxWorldX = Math.min(chunkMinX + 15, (int) Math.ceil(centerX + horizontalRadius + 1.0));
        int minWorldZ = Math.max(chunkMinZ, (int) Math.floor(centerZ - horizontalRadius - 1.0));
        int maxWorldZ = Math.min(chunkMinZ + 15, (int) Math.ceil(centerZ + horizontalRadius + 1.0));
        int minCarveY = Math.max(minY, (int) Math.floor(centerY - verticalRadius - 1.0));
        int maxCarveY = Math.min(maxY, (int) Math.ceil(centerY + verticalRadius + 1.0));
        if (minWorldX > maxWorldX || minWorldZ > maxWorldZ || minCarveY > maxCarveY) {
            return;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        double invHorizontal = 1.0 / Math.max(0.001, horizontalRadius);
        double invVertical = 1.0 / Math.max(0.001, verticalRadius);
        for (int worldZ = minWorldZ; worldZ <= maxWorldZ; worldZ++) {
            double dz = (worldZ + 0.5 - centerZ) * invHorizontal;
            double dz2 = dz * dz;
            for (int worldX = minWorldX; worldX <= maxWorldX; worldX++) {
                double dx = (worldX + 0.5 - centerX) * invHorizontal;
                double horizontal = dx * dx + dz2;
                if (horizontal >= 1.0) {
                    continue;
                }
                int localX = worldX - chunkMinX;
                int localZ = worldZ - chunkMinZ;
                for (int y = minCarveY; y <= maxCarveY; y++) {
                    double dy = (y + 0.5 - centerY) * invVertical;
                    if (horizontal + dy * dy >= 1.0) {
                        continue;
                    }
                    cursor.set(localX, y, localZ);
                    BlockState existing = chunk.getBlockState(cursor);
                    if (isDeepCarverReplaceable(existing)) {
                        chunk.setBlockState(cursor, Blocks.AIR.defaultBlockState(), false);
                    }
                }
            }
        }
    }

    private static boolean isDeepCarverReplaceable(BlockState state) {
        return !state.isAir()
            && !state.is(Blocks.WATER)
            && !state.is(Blocks.LAVA)
            && !state.is(Blocks.BEDROCK);
    }

    @Override
    public int getSeaLevel() {
        return seaLevelY;
    }

    @Override
    public int getMinY() {
        return minY;
    }

    @Override
    public int getGenDepth() {
        return genDepth;
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        long startNanos = System.nanoTime();
        BaseColumnPlan plan = baseColumnPlan(x, z, level.getMinBuildHeight());
        int top = plan.river().hasWater() ? plan.river().waterSurfaceY() : plan.river().terrainSurfaceY();
        int result = Math.clamp(top + 1, level.getMinBuildHeight(), level.getMaxBuildHeight());
        logTiming("getBaseHeight", new ChunkPos(Math.floorDiv(x, 16), Math.floorDiv(z, 16)), startNanos);
        return result;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState random) {
        long startNanos = System.nanoTime();
        int minBuildY = height.getMinBuildHeight();
        BaseColumnPlan plan = baseColumnPlan(x, z, minBuildY);
        boolean steep = false;
        BlockState[] states = new BlockState[height.getHeight()];
        for (int i = 0; i < states.length; i++) {
            int y = minBuildY + i;
            states[i] = ChunkTerrainPlanner.columnStateFor(y, plan.river().terrainSurfaceY(), minBuildY, plan.surfaceRock(), plan.exposedSurfaceRock(), steep, plan.river(), plan.surfaceY(), false, plan.vegetationClass(), seaLevelY);
        }
        logTiming("getBaseColumn", new ChunkPos(Math.floorDiv(x, 16), Math.floorDiv(z, 16)), startNanos);
        return new NoiseColumn(minBuildY, states);
    }

    private BaseColumnPlan baseColumnPlan(int x, int z, int minBuildY) {
        BaseQueryKey key = new BaseQueryKey(x, z, minBuildY);
        BaseColumnPlan cached = baseColumnCache.get(key);
        if (cached != null) {
            return cached;
        }
        RuntimeData runtime = data();
        int surface = surfaceY(x, z);
        boolean hasHeightData = runtime != null && hasHeightData(runtime, null, x, z);
        int vegetation = hasHeightData ? sampleVegetationClass(runtime, x, z) : 0;
        RiverShape river = hasHeightData ? computeSurfaceWaterShape(runtime, null, x, z, surface, minBuildY, vegetation) : RiverShape.none(surface);
        BlockState surfaceRock = hasHeightData ? sampleSurfaceRock(runtime, x, z, surface) : defaultBaseRock(surface);
        BlockState exposedSurfaceRock = ChunkTerrainPlanner.exposedSurfaceRock(x, z, surfaceRock);
        BaseColumnPlan plan = new BaseColumnPlan(surface, vegetation, river, surfaceRock, exposedSurfaceRock);
        BaseColumnPlan existing = baseColumnCache.putIfAbsent(key, plan);
        return existing == null ? plan : existing;
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
        info.add("UKGeo surface: " + surfaceY(pos.getX(), pos.getZ()));
    }

    record RuntimeData(
        TileManifest manifest,
        R16HeightTileLayer height,
        U8OreTileLayer surfaceLayer,
        U8OreTileLayer vegetationLayer,
        U8OreTileLayer biomeRegionLayer,
        U8OreTileLayer riverLayer,
        U8OreTileLayer riverOrderLayer,
        U8OreTileLayer riverHalfWidthLayer,
        Map<String, U8OreTileLayer> oreLayers,
        List<OreDefinition> ores
    ) {
    }

    public record RiverShape(boolean hasWater, boolean influenced, int terrainSurfaceY, int waterSurfaceY, BlockState floorMaterial) {
        static RiverShape none(int surfaceY) {
            return new RiverShape(false, false, surfaceY, surfaceY, Blocks.GRAVEL.defaultBlockState());
        }
    }

    private record RiverDistance(double blocks, int score, int halfWidth) {
        static RiverDistance none() {
            return new RiverDistance(Double.POSITIVE_INFINITY, 0, 0);
        }

        boolean found() {
            return score > 0;
        }
    }

    private record LakeDistance(double blocks, int waterSurfaceY) {
        static LakeDistance none() {
            return new LakeDistance(Double.POSITIVE_INFINITY, Integer.MIN_VALUE);
        }

        boolean found() {
            return waterSurfaceY != Integer.MIN_VALUE;
        }
    }

    private record WaterDistance(double blocks) {
    }

    private record LandDistance(double blocks) {
    }

    private record BlockStatePair(BlockState normal, BlockState deepslate) {
    }

    private record OptionalBlock(BlockState state) {
    }
}
