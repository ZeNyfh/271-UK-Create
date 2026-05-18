package com.ukgeo.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
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
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.fml.ModList;

public final class UkGeoChunkGenerator extends ChunkGenerator {
    private static final String CREATE_DIESEL_GENERATORS_MOD_ID = "createdieselgenerators";
    private static final String CREATE_DIESEL_GENERATORS_OIL_DATA_CLASS = "com.jesz.createdieselgenerators.world.OilChunksSavedData";
    private static final int OIL_SCORE_THRESHOLD = 64;
    private static final int OIL_DEPOSIT_MIN_MILLIBUCKETS = 4_250_000;
    private static final int OIL_DEPOSIT_MAX_MILLIBUCKETS = 9_500_000;
    private static final int[] OIL_SAMPLE_OFFSETS = {4, 8, 12};
    private static final int VEGETATION_BROADLEAF_WOODLAND = 1;
    private static final int VEGETATION_CONIFER_WOODLAND = 2;
    private static final int VEGETATION_ARABLE = 3;
    private static final int VEGETATION_IMPROVED_GRASSLAND = 4;
    private static final int VEGETATION_NEUTRAL_GRASSLAND = 5;
    private static final int VEGETATION_CALCAREOUS_GRASSLAND = 6;
    private static final int VEGETATION_ACID_GRASSLAND = 7;
    private static final int VEGETATION_WETLAND = 8;
    private static final int VEGETATION_HEATH = 9;
    private static final int VEGETATION_URBAN = 11;
    private static final int VEGETATION_ROCKY = 12;
    private static final int WATER_EDGE_SMOOTHING_RADIUS = 16;
    private static final int WATER_EDGE_SAMPLE_STEP = 4;
    private static final int WATER_EDGE_MAX_LAND_HEIGHT_ABOVE_SEA = 24;
    private static final int SHALLOW_WATER_DEPTH = 2;
    private static final double BACKGROUND_ORE_ATTEMPT_MULTIPLIER = 0.1;
    private static final double ORE_AREA_ATTEMPT_MULTIPLIER = 3.0;
    private static final int SNOW_ICE_MIN_Y = 550;
    private static final int VANILLA_MIN_Y = -64;
    private static final int VANILLA_MAX_Y = 320;
    private static volatile boolean createDieselGeneratorsOilLookupAttempted;
    private static volatile Method createDieselGeneratorsSetOilAmount;
    private static final BlockState PERSISTENT_OAK_LEAVES = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
    private static final BlockState PERSISTENT_BIRCH_LEAVES = Blocks.BIRCH_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
    private static final BlockState PERSISTENT_SPRUCE_LEAVES = Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
    private static final BlockState PERSISTENT_DARK_OAK_LEAVES = Blocks.DARK_OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);

    public static final MapCodec<UkGeoChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
        BiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.biomeSource),
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
        Codec.INT.optionalFieldOf("gen_depth", 1628).forGetter(generator -> generator.genDepth),
        Codec.INT.optionalFieldOf("fallback_height", 72).forGetter(generator -> generator.fallbackHeight),
        Codec.BOOL.optionalFieldOf("use_config_data_root", true).forGetter(generator -> generator.useConfigDataRoot)
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
    private volatile RuntimeData runtimeData;
    private volatile boolean attemptedDataLoad;
    private final Map<String, Optional<BlockStatePair>> blockStateCache = new ConcurrentHashMap<>();
    private final Map<Integer, BlockState> surfaceBlockCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ChunkTerrainPlanner.Plan> chunkPlans = new ConcurrentHashMap<>();

    public UkGeoChunkGenerator(
        BiomeSource biomeSource,
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
        int fallbackHeight,
        boolean useConfigDataRoot
    ) {
        super(biomeSource, UkGeoChunkGenerator::sanitizeBiomeSettings);
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
        this.useConfigDataRoot = useConfigDataRoot;
    }

    private static BiomeGenerationSettings sanitizeBiomeSettings(Holder<Biome> biome) {
        List<HolderSet<PlacedFeature>> sanitized = new ArrayList<>();
        for (HolderSet<PlacedFeature> step : biome.value().getGenerationSettings().features()) {
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
        return new BiomeGenerationSettings(Map.of(), List.copyOf(sanitized));
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
        RuntimeData data = data();
        if (data == null) {
            return CompletableFuture.completedFuture(chunk);
        }
        return CompletableFuture
            .supplyAsync(
                Util.wrapThreadWithTaskName("ukgeo_plan", () -> {
                    try {
                        return ChunkTerrainPlanner.compute(this, data, chunk);
                    } catch (IOException ex) {
                        throw new RuntimeException("Failed to plan UKGeo chunk " + chunk.getPos(), ex);
                    }
                }),
                Util.backgroundExecutor()
            )
            .thenApply(plan -> {
                chunkPlans.put(chunk.getPos().toLong(), plan);
                ChunkTerrainPlanner.apply(plan, chunk);
                return chunk;
            });
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

    private int riverWaterSurfaceY(HeightTileWindow heightWindow, int x, int z, int originalSurfaceY, int riverBedY) {
        int smoothedSurface = smoothedSurfaceY(heightWindow, x, z, Math.max(2, riverWidenRadius + 2)).orElse(originalSurfaceY);
        int target = Math.round((originalSurfaceY * 0.45f) + (smoothedSurface * 0.55f)) - 1;
        return Math.clamp(target, riverBedY + 1, originalSurfaceY - 1);
    }

    RiverShape computeRiverShape(RuntimeData data, HeightTileWindow heightWindow, int x, int z, int originalSurfaceY, int minBuildY) {
        if (data.riverLayer == null) {
            return RiverShape.none(originalSurfaceY);
        }
        int waterRadius = riverWidenRadius;
        int bankRadius = waterRadius + 2;
        RiverDistance distance = nearestRiver(data, x, z, bankRadius);
        if (!distance.found()) {
            return RiverShape.none(originalSurfaceY);
        }
        if (distance.blocks() <= waterRadius) {
            int depth = riverChannelDepth(distance.blocks(), waterRadius);
            int bed = riverBedY(originalSurfaceY, minBuildY, depth);
            return new RiverShape(true, true, bed, riverWaterSurfaceY(heightWindow, x, z, originalSurfaceY, bed));
        }
        int bankDrop = riverBankDrop(distance.blocks(), waterRadius, bankRadius);
        if (bankDrop <= 0) {
            return RiverShape.none(originalSurfaceY);
        }
        int terrainSurfaceY = Math.max(minBuildY + 1, originalSurfaceY - bankDrop);
        return new RiverShape(false, true, terrainSurfaceY, terrainSurfaceY);
    }

    int sampleVegetationClass(RuntimeData data, int x, int z) {
        if (data.vegetationLayer == null) {
            return 0;
        }
        return data.vegetationLayer.sample(x, z).orElse(0);
    }

    BlockState sampleSurfaceRock(RuntimeData data, int x, int z, int y) {
        if (data.surfaceLayer == null) {
            return y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
        }
        int classId = data.surfaceLayer.sample(x, z).orElse(0);
        if (classId == 0) {
            return y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
        }
        return surfaceBlockCache.computeIfAbsent(classId, id -> resolveSurfaceBlock(data, id, y));
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
            int score = scoreLayer == null ? 0 : scoreLayer.sample(pos.getMinBlockX() + 8, pos.getMinBlockZ() + 8).orElse(0);
            int attempts = scaledOreAttempts(normalOreAttempts(ore, score), score > 0, random);
            for (int attempt = 0; attempt < attempts; attempt++) {
                int localX = random.nextInt(16);
                int localZ = random.nextInt(16);
                ChunkTerrainPlanner.ColumnPlan column = columns[localZ * 16 + localX];
                int top = column.terrainTop();
                int bandMin = scaleVanillaY(ore.vanillaMinY(), chunk);
                int bandMax = scaleVanillaY(ore.vanillaMaxY(), chunk);
                int min = Math.max(chunk.getMinBuildHeight() + 1, bandMin);
                int max = Math.min(top - 1, bandMax);
                if (min > max) {
                    continue;
                }
                int y = min + random.nextInt(max - min + 1);
                for (int i = 0; i < ore.veinSize(); i++) {
                    int px = Math.clamp(localX + random.nextInt(5) - 2, 0, 15);
                    int pz = Math.clamp(localZ + random.nextInt(5) - 2, 0, 15);
                    int py = Math.clamp(y + random.nextInt(5) - 2, chunk.getMinBuildHeight() + 1, chunk.getMaxBuildHeight() - 1);
                    BlockState oreState = py < 0 ? states.get().deepslate : states.get().normal;
                    placements.add(new ChunkTerrainPlanner.OrePlacement(px, py, pz, oreState));
                }
            }
        }
        return placements.toArray(ChunkTerrainPlanner.OrePlacement[]::new);
    }

    private RiverDistance nearestRiver(RuntimeData data, int x, int z, int radius) {
        double bestDistance = Double.POSITIVE_INFINITY;
        int bestScore = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int distanceSquared = dx * dx + dz * dz;
                if (distanceSquared > radius * radius) {
                    continue;
                }
                int score = data.riverLayer.sample(x + dx, z + dz).orElse(0);
                if (score <= 0) {
                    continue;
                }
                double distance = Math.sqrt(distanceSquared);
                if (distance < bestDistance || (distance == bestDistance && score > bestScore)) {
                    bestDistance = distance;
                    bestScore = score;
                }
            }
        }
        return bestScore <= 0 ? RiverDistance.none() : new RiverDistance(bestDistance, bestScore);
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
        int classId = data.surfaceLayer.sample(x, z).orElse(0);
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
        OptionalInt decimetres = data.height.sampleDecimetres(x, z);
        if (decimetres.isEmpty()) {
            return waterFloorY(data, x, z, nodataSurfaceY);
        }
        int rawSurfaceY = rawSurfaceY(x, z, decimetres.getAsInt());
        return waterSmoothedSurfaceY(data, null, x, z, decimetres.getAsInt(), rawSurfaceY);
    }

    int computeSurfaceY(RuntimeData data, HeightTileWindow heightWindow, int x, int z) {
        OptionalInt decimetres = heightWindow.decimetres(x, z);
        if (decimetres.isEmpty()) {
            return waterFloorY(data, heightWindow, x, z, nodataSurfaceY);
        }
        int rawSurfaceY = rawSurfaceY(heightWindow, x, z, decimetres.getAsInt());
        return waterSmoothedSurfaceY(data, heightWindow, x, z, decimetres.getAsInt(), rawSurfaceY);
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
                OptionalInt sample = sampleDecimetres(heightWindow, x + dx, z + dz);
                if (sample.isPresent() && sample.getAsInt() > 0) {
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
                OptionalInt sample = sampleDecimetres(heightWindow, x + dx, z + dz);
                if (sample.isEmpty() || sample.getAsInt() <= 0) {
                    continue;
                }
                if ((double) distanceSquared < bestDistanceSquared) {
                    bestDistanceSquared = distanceSquared;
                }
            }
        }
        return Double.isInfinite(bestDistanceSquared) ? Optional.empty() : Optional.of(new LandDistance(Math.sqrt(bestDistanceSquared)));
    }

    private OptionalInt sampleDecimetres(HeightTileWindow heightWindow, int x, int z) {
        if (heightWindow != null) {
            return heightWindow.decimetres(x, z);
        }
        RuntimeData data = data();
        return data == null ? OptionalInt.empty() : data.height.sampleDecimetres(x, z);
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
                OptionalInt sample = sampleDecimetres(heightWindow, x + dx, z + dz);
                if (sample.isPresent()) {
                    total += sample.getAsInt() / 10.0;
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
                OptionalInt sample = sampleDecimetres(heightWindow, x + dx, z + dz);
                if (sample.isPresent()) {
                    total += seaLevelY + Math.round((float) shapedHeightMetres(heightWindow, x + dx, z + dz, sample.getAsInt() / 10.0));
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

    private static int normalOreAttempts(OreDefinition ore, int score) {
        if (score <= 0) {
            return ore.baseAttempts() + ore.maxBonusAttempts();
        }
        return ore.baseAttempts() + Math.round(ore.maxBonusAttempts() * (score / 255.0f));
    }

    private static int scaledOreAttempts(int normalAttempts, boolean inOreArea, java.util.Random random) {
        double multiplier = inOreArea ? ORE_AREA_ATTEMPT_MULTIPLIER : BACKGROUND_ORE_ATTEMPT_MULTIPLIER;
        double scaled = normalAttempts * multiplier;
        int wholeAttempts = (int) scaled;
        double fractionalAttempt = scaled - wholeAttempts;
        if (fractionalAttempt <= 0.0) {
            return wholeAttempts;
        }
        return wholeAttempts + (random.nextDouble() < fractionalAttempt ? 1 : 0);
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
                U8OreTileLayer riverLayer = manifest.riversPath == null ? null : new U8OreTileLayer(manifest, "rivers", manifest.riversPath);
                runtimeData = new RuntimeData(manifest, new R16HeightTileLayer(manifest), surfaceLayer, vegetationLayer, riverLayer, layers, OreSettings.defaults());
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
        return "tiles=%dx%d tileSize=%d heightScale=%.3f lowExtra=%.3f highScale=%.3f nodataY=%d riverRadius=%d riverDepth=%d vegetation=%s heightCache=%s".formatted(
            data.manifest.tilesX(),
            data.manifest.tilesZ(),
            data.manifest.tileSize,
            heightScale,
            lowlandExtraScale,
            highlandScale,
            nodataSurfaceY,
            riverWidenRadius,
            riverCarveDepth,
            data.vegetationLayer == null ? "none" : "loaded",
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
            result.put(entry.getKey(), entry.getValue().sample(x, z).orElse(0));
        }
        return result;
    }

    public String sampleSurface(int x, int z) {
        RuntimeData data = data();
        if (data == null || data.surfaceLayer == null) {
            return "none";
        }
        int classId = data.surfaceLayer.sample(x, z).orElse(0);
        SurfaceGeologyClass surfaceClass = data.manifest.surfaceGeologyClasses.get(classId);
        return surfaceClass == null ? Integer.toString(classId) : surfaceClass.name() + "(" + classId + ")";
    }

    public String sampleVegetation(int x, int z) {
        RuntimeData data = data();
        if (data == null || data.vegetationLayer == null) {
            return "none";
        }
        int classId = data.vegetationLayer.sample(x, z).orElse(0);
        VegetationClass vegetationClass = data.manifest.vegetationClasses.get(classId);
        return vegetationClass == null ? Integer.toString(classId) : vegetationClass.name() + "(" + classId + ")";
    }

    public int sampleRiver(int x, int z) {
        RuntimeData data = data();
        if (data == null || data.riverLayer == null) {
            return 0;
        }
        return data.riverLayer.sample(x, z).orElse(0);
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
        return data.vegetationLayer.sample(x, z).orElse(0);
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureManager, RandomState random, ChunkAccess chunk) {
        ChunkTerrainPlanner.Plan plan = chunkPlans.remove(chunk.getPos().toLong());
        if (plan != null) {
            scheduleWaterTicks(level, chunk, plan);
            placeVegetation(chunk, plan);
        } else {
            scheduleWaterTicks(level, chunk);
            placeVegetation(chunk);
        }
        placeHighAltitudeSnowAndIce(chunk);
        populateCreateDieselGeneratorsOil(level.getLevel(), chunk.getPos());
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
    }

    private static int scaleVanillaY(int vanillaY, LevelHeightAccessor level) {
        int worldMin = level.getMinBuildHeight();
        int worldMax = level.getMaxBuildHeight() - 1;
        float amount = (vanillaY - VANILLA_MIN_Y) / (float) (VANILLA_MAX_Y - VANILLA_MIN_Y);
        return worldMin + Math.round(amount * (worldMax - worldMin));
    }

    private void placeVegetation(ChunkAccess chunk, ChunkTerrainPlanner.Plan plan) {
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
        int cellBlocks = data.manifest.vegetationCellBlocks;
        for (int localX = 0; localX < 16; localX++) {
            int worldX = pos.getMinBlockX() + localX;
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldZ = pos.getMinBlockZ() + localZ;
                if (Math.floorMod(worldX, cellBlocks) != 0 || Math.floorMod(worldZ, cellBlocks) != 0) {
                    continue;
                }
                ChunkTerrainPlanner.ColumnPlan column = plan.columns()[localZ * 16 + localX];
                int vegetationClass = column.vegetationClass();
                if (vegetationClass == VEGETATION_URBAN) {
                    continue;
                }
                int top = surfaceMap.getHighestTaken(localX, localZ);
                if (top < minBuildY + 1 || top >= maxY) {
                    continue;
                }
                UkGeoChunkGenerator.RiverShape river = column.river();
                if (river.hasWater() || top <= seaLevelY || river.terrainSurfaceY() != top) {
                    continue;
                }
                BlockState ground = chunk.getBlockState(cursor.set(localX, top, localZ));
                if (!ground.is(Blocks.GRASS_BLOCK) && !ground.is(Blocks.DIRT)) {
                    continue;
                }
                int y = top + 1;
                if (y >= maxY || !chunk.getBlockState(cursor.set(localX, y, localZ)).isAir()) {
                    continue;
                }
                placeVegetationForClass(chunk, cursor, random, vegetationClass, localX, y, localZ);
            }
        }
    }

    private void placeVegetation(ChunkAccess chunk) {
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
        int cellBlocks = data.manifest.vegetationCellBlocks;
        for (int localX = 0; localX < 16; localX++) {
            int worldX = pos.getMinBlockX() + localX;
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldZ = pos.getMinBlockZ() + localZ;
                if (Math.floorMod(worldX, cellBlocks) != 0 || Math.floorMod(worldZ, cellBlocks) != 0) {
                    continue;
                }
                int vegetationClass = data.vegetationLayer.sample(worldX, worldZ).orElse(0);
                if (vegetationClass == VEGETATION_URBAN) {
                    continue;
                }
                int top = surfaceMap.getHighestTaken(localX, localZ);
                if (top < minBuildY + 1 || top >= maxY) {
                    continue;
                }
                RuntimeData runtime = data();
                RiverShape river = runtime == null ? RiverShape.none(top) : computeRiverShape(runtime, null, worldX, worldZ, top, minBuildY);
                if (river.hasWater() || top <= seaLevelY || river.terrainSurfaceY() != top) {
                    continue;
                }
                BlockState ground = chunk.getBlockState(cursor.set(localX, top, localZ));
                if (!ground.is(Blocks.GRASS_BLOCK) && !ground.is(Blocks.DIRT)) {
                    continue;
                }
                int y = top + 1;
                if (y >= maxY || !chunk.getBlockState(cursor.set(localX, y, localZ)).isAir()) {
                    continue;
                }
                placeVegetationForClass(chunk, cursor, random, vegetationClass, localX, y, localZ);
            }
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
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int y = chunk.getMinBuildHeight(); y < SNOW_ICE_MIN_Y; y++) {
                    BlockState state = chunk.getBlockState(cursor.set(localX, y, localZ));
                    if (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.ICE) || state.is(Blocks.FROSTED_ICE) || state.is(Blocks.POWDER_SNOW)) {
                        chunk.setBlockState(cursor, Blocks.AIR.defaultBlockState(), false);
                    }
                }
            }
        }
    }

    private void placeVegetationForClass(ChunkAccess chunk, BlockPos.MutableBlockPos cursor, java.util.Random random, int vegetationClass, int localX, int y, int localZ) {
        switch (vegetationClass) {
            case VEGETATION_BROADLEAF_WOODLAND -> {
                if (canPlaceTreeAt(localX, localZ) && random.nextInt(88) == 0) {
                    placeSimpleTree(chunk, cursor, random, localX, y, localZ, random.nextInt(4) == 0 ? VegetationTreeKind.BIRCH : VegetationTreeKind.OAK);
                } else if (random.nextInt(9) == 0) {
                    placeShrub(chunk, cursor, random, localX, y, localZ, random.nextBoolean() ? PERSISTENT_OAK_LEAVES : PERSISTENT_BIRCH_LEAVES);
                } else if (random.nextInt(6) == 0) {
                    placePlant(chunk, cursor, localX, y, localZ, randomGrassOrFlower(random, vegetationClass));
                }
            }
            case VEGETATION_CONIFER_WOODLAND -> {
                if (canPlaceTreeAt(localX, localZ) && random.nextInt(76) == 0) {
                    placeSimpleTree(chunk, cursor, random, localX, y, localZ, VegetationTreeKind.SPRUCE);
                } else if (random.nextInt(10) == 0) {
                    placeShrub(chunk, cursor, random, localX, y, localZ, PERSISTENT_SPRUCE_LEAVES);
                } else if (random.nextInt(5) == 0) {
                    if (random.nextInt(5) == 0) {
                        placeDoublePlant(chunk, cursor, localX, y, localZ, Blocks.LARGE_FERN.defaultBlockState());
                    } else {
                        placePlant(chunk, cursor, localX, y, localZ, Blocks.FERN.defaultBlockState());
                    }
                }
            }
            case VEGETATION_WETLAND -> {
                if (hasAdjacentWater(chunk, cursor, localX, y - 1, localZ) && random.nextInt(3) == 0) {
                    placeSugarCane(chunk, cursor, random, localX, y, localZ);
                } else if (hasAdjacentWater(chunk, cursor, localX, y - 1, localZ) && random.nextInt(8) == 0) {
                    placeLilyPadNearWater(chunk, cursor, localX, y - 1, localZ);
                } else if (random.nextInt(4) == 0) {
                    placePlant(chunk, cursor, localX, y, localZ, randomWetlandPlant(random));
                } else if (random.nextInt(12) == 0) {
                    placeDoublePlant(chunk, cursor, localX, y, localZ, Blocks.LARGE_FERN.defaultBlockState());
                }
            }
            case VEGETATION_HEATH -> {
                if (random.nextInt(11) == 0) {
                    placeShrub(chunk, cursor, random, localX, y, localZ, random.nextBoolean() ? PERSISTENT_DARK_OAK_LEAVES : PERSISTENT_OAK_LEAVES);
                } else if (random.nextInt(5) == 0) {
                    placePlant(chunk, cursor, localX, y, localZ, random.nextInt(3) == 0 ? Blocks.DEAD_BUSH.defaultBlockState() : Blocks.FERN.defaultBlockState());
                }
            }
            case VEGETATION_ARABLE -> {
                if (random.nextInt(18) == 0) {
                    placePlant(chunk, cursor, localX, y, localZ, randomGrassOrFlower(random, vegetationClass));
                }
            }
            case VEGETATION_IMPROVED_GRASSLAND -> {
                if (random.nextInt(5) == 0) {
                    placePlant(chunk, cursor, localX, y, localZ, randomGrassOrFlower(random, vegetationClass));
                } else if (random.nextInt(35) == 0) {
                    placeDoublePlant(chunk, cursor, localX, y, localZ, Blocks.TALL_GRASS.defaultBlockState());
                }
            }
            case VEGETATION_NEUTRAL_GRASSLAND, VEGETATION_CALCAREOUS_GRASSLAND -> {
                if (random.nextInt(4) == 0) {
                    placePlant(chunk, cursor, localX, y, localZ, randomGrassOrFlower(random, vegetationClass));
                } else if (random.nextInt(30) == 0) {
                    placeDoublePlant(chunk, cursor, localX, y, localZ, Blocks.TALL_GRASS.defaultBlockState());
                } else if (random.nextInt(70) == 0) {
                    placeShrub(chunk, cursor, random, localX, y, localZ, PERSISTENT_OAK_LEAVES);
                }
            }
            case VEGETATION_ACID_GRASSLAND -> {
                if (random.nextInt(5) == 0) {
                    placePlant(chunk, cursor, localX, y, localZ, random.nextBoolean() ? Blocks.FERN.defaultBlockState() : Blocks.SHORT_GRASS.defaultBlockState());
                } else if (random.nextInt(45) == 0) {
                    placeDoublePlant(chunk, cursor, localX, y, localZ, Blocks.LARGE_FERN.defaultBlockState());
                }
            }
            case 0, VEGETATION_ROCKY -> {
                if (random.nextInt(10) == 0) {
                    placePlant(chunk, cursor, localX, y, localZ, Blocks.SHORT_GRASS.defaultBlockState());
                }
            }
            default -> {
            }
        }
    }

    private static void placePlant(ChunkAccess chunk, BlockPos.MutableBlockPos cursor, int localX, int y, int localZ, BlockState state) {
        if (y < chunk.getMaxBuildHeight() && chunk.getBlockState(cursor.set(localX, y, localZ)).isAir()) {
            chunk.setBlockState(cursor, state, false);
        }
    }

    private static void placeDoublePlant(ChunkAccess chunk, BlockPos.MutableBlockPos cursor, int localX, int y, int localZ, BlockState state) {
        if (y + 1 >= chunk.getMaxBuildHeight()) {
            return;
        }
        if (!chunk.getBlockState(cursor.set(localX, y, localZ)).isAir() || !chunk.getBlockState(cursor.set(localX, y + 1, localZ)).isAir()) {
            return;
        }
        chunk.setBlockState(cursor.set(localX, y, localZ), state.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER), false);
        chunk.setBlockState(cursor.set(localX, y + 1, localZ), state.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER), false);
    }

    private static void placeSugarCane(ChunkAccess chunk, BlockPos.MutableBlockPos cursor, java.util.Random random, int localX, int y, int localZ) {
        int height = 1 + random.nextInt(3);
        for (int dy = 0; dy < height; dy++) {
            int py = y + dy;
            if (py >= chunk.getMaxBuildHeight() || !chunk.getBlockState(cursor.set(localX, py, localZ)).isAir()) {
                return;
            }
        }
        BlockState state = Blocks.SUGAR_CANE.defaultBlockState();
        for (int dy = 0; dy < height; dy++) {
            chunk.setBlockState(cursor.set(localX, y + dy, localZ), state, false);
        }
    }

    private static void placeLilyPadNearWater(ChunkAccess chunk, BlockPos.MutableBlockPos cursor, int localX, int waterY, int localZ) {
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
                return;
            }
        }
    }

    private static void placeSimpleTree(ChunkAccess chunk, BlockPos.MutableBlockPos cursor, java.util.Random random, int localX, int y, int localZ, VegetationTreeKind kind) {
        int height = switch (kind) {
            case SPRUCE -> 6 + random.nextInt(4);
            case BIRCH -> 5 + random.nextInt(3);
            case OAK -> 4 + random.nextInt(3);
        };
        if (y + height + 2 >= chunk.getMaxBuildHeight()) {
            return;
        }
        for (int dy = 0; dy < height; dy++) {
            if (!chunk.getBlockState(cursor.set(localX, y + dy, localZ)).isAir()) {
                return;
            }
        }
        BlockState log = switch (kind) {
            case SPRUCE -> Blocks.SPRUCE_LOG.defaultBlockState();
            case BIRCH -> Blocks.BIRCH_LOG.defaultBlockState();
            case OAK -> Blocks.OAK_LOG.defaultBlockState();
        };
        BlockState leaves = switch (kind) {
            case SPRUCE -> PERSISTENT_SPRUCE_LEAVES;
            case BIRCH -> PERSISTENT_BIRCH_LEAVES;
            case OAK -> PERSISTENT_OAK_LEAVES;
        };
        for (int dy = 0; dy < height; dy++) {
            chunk.setBlockState(cursor.set(localX, y + dy, localZ), log, false);
        }
        int leafBase = y + height - (kind == VegetationTreeKind.SPRUCE ? 3 : 2);
        int leafTop = y + height + (kind == VegetationTreeKind.SPRUCE ? 1 : 2);
        for (int py = leafBase; py <= leafTop; py++) {
            int layer = py - leafBase;
            int radius = kind == VegetationTreeKind.SPRUCE ? spruceLeafRadius(layer, leafTop - leafBase) : (py == leafTop ? 1 : 2);
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    int px = localX + dx;
                    int pz = localZ + dz;
                    if (px < 0 || px > 15 || pz < 0 || pz > 15 || Math.abs(dx) + Math.abs(dz) > radius + 1) {
                        continue;
                    }
                    if (chunk.getBlockState(cursor.set(px, py, pz)).isAir()) {
                        chunk.setBlockState(cursor, leaves, false);
                    }
                }
            }
        }
    }

    private static int spruceLeafRadius(int layer, int topLayer) {
        if (layer >= topLayer) {
            return 1;
        }
        return layer % 2 == 0 ? 2 : 1;
    }

    private static void placeShrub(ChunkAccess chunk, BlockPos.MutableBlockPos cursor, java.util.Random random, int localX, int y, int localZ, BlockState leaves) {
        if (!chunk.getBlockState(cursor.set(localX, y, localZ)).isAir()) {
            return;
        }
        if (random.nextInt(3) == 0) {
            chunk.setBlockState(cursor.set(localX, y, localZ), Blocks.OAK_LOG.defaultBlockState(), false);
        }
        int radius = random.nextBoolean() ? 1 : 2;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int px = localX + dx;
                int pz = localZ + dz;
                if (px < 0 || px > 15 || pz < 0 || pz > 15 || Math.abs(dx) + Math.abs(dz) > radius + 1) {
                    continue;
                }
                int py = y + (Math.abs(dx) + Math.abs(dz) <= 1 && random.nextBoolean() ? 1 : 0);
                if (py < chunk.getMaxBuildHeight() && chunk.getBlockState(cursor.set(px, py, pz)).isAir()) {
                    chunk.setBlockState(cursor, leaves, false);
                }
            }
        }
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

    private static boolean canPlaceTreeAt(int localX, int localZ) {
        return localX >= 2 && localX <= 13 && localZ >= 2 && localZ <= 13;
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
                RiverShape river = computeRiverShape(data(), null, worldX, worldZ, top, minBuildY);
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
        for (int y = fromY; y <= toY; y++) {
            cursor.set(worldX, y, worldZ);
            if (chunk.getBlockState(cursor).getFluidState().is(Fluids.WATER)) {
                level.scheduleTick(cursor.immutable(), Fluids.WATER, tickDelay);
            }
        }
    }

    private void populateCreateDieselGeneratorsOil(ServerLevel level, ChunkPos chunkPos) {
        RuntimeData data = data();
        Optional<Method> setOilAmount = createDieselGeneratorsSetOilAmount();
        if (data == null || setOilAmount.isEmpty()) {
            return;
        }
        int amount = oilAmountForChunk(data, level.getSeed(), chunkPos);
        try {
            setOilAmount.get().invoke(null, level, chunkPos, amount);
        } catch (ReflectiveOperationException ex) {
            UkGeoMod.LOGGER.warn("Could not set Create: Diesel Generators oil amount for chunk {}: {}", chunkPos, ex.getMessage());
        }
    }

    private static Optional<Method> createDieselGeneratorsSetOilAmount() {
        if (!ModList.get().isLoaded(CREATE_DIESEL_GENERATORS_MOD_ID)) {
            return Optional.empty();
        }
        Method current = createDieselGeneratorsSetOilAmount;
        if (current != null) {
            return Optional.of(current);
        }
        if (createDieselGeneratorsOilLookupAttempted) {
            return Optional.empty();
        }
        synchronized (UkGeoChunkGenerator.class) {
            if (createDieselGeneratorsSetOilAmount != null) {
                return Optional.of(createDieselGeneratorsSetOilAmount);
            }
            if (createDieselGeneratorsOilLookupAttempted) {
                return Optional.empty();
            }
            createDieselGeneratorsOilLookupAttempted = true;
            try {
                Class<?> savedDataClass = Class.forName(CREATE_DIESEL_GENERATORS_OIL_DATA_CLASS);
                createDieselGeneratorsSetOilAmount = savedDataClass.getMethod("setChunkOilAmount", ServerLevel.class, ChunkPos.class, int.class);
                UkGeoMod.LOGGER.info("Create: Diesel Generators oil integration enabled");
                return Optional.of(createDieselGeneratorsSetOilAmount);
            } catch (ReflectiveOperationException ex) {
                UkGeoMod.LOGGER.warn("Create: Diesel Generators is loaded, but UKGeo could not find its oil chunk API: {}", ex.getMessage());
                return Optional.empty();
            }
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
        return layer == null ? 0 : layer.sample(x, z).orElse(0);
    }

    private static int surfaceOilScore(RuntimeData data, int x, int z) {
        if (data.surfaceLayer == null) {
            return 0;
        }
        int classId = data.surfaceLayer.sample(x, z).orElse(0);
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

    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
    }

    @Override
    public void applyCarvers(WorldGenRegion level, long seed, RandomState random, BiomeManager biomeManager, StructureManager structureManager, ChunkAccess chunk, GenerationStep.Carving carving) {
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
        int surface = surfaceY(x, z);
        RuntimeData runtime = data();
        int minBuildY = level.getMinBuildHeight();
        RiverShape river = runtime == null ? RiverShape.none(surface) : computeRiverShape(runtime, null, x, z, surface, minBuildY);
        int top = river.hasWater() ? river.waterSurfaceY() : river.terrainSurfaceY();
        return Math.clamp(top + 1, level.getMinBuildHeight(), level.getMaxBuildHeight());
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState random) {
        RuntimeData runtime = data();
        int surface = surfaceY(x, z);
        int minBuildY = height.getMinBuildHeight();
        BlockState surfaceRock = runtime == null
            ? Blocks.STONE.defaultBlockState()
            : sampleSurfaceRock(runtime, x, z, surface);
        int vegetation = runtime == null ? 0 : sampleVegetationClass(runtime, x, z);
        RiverShape river = runtime == null ? RiverShape.none(surface) : computeRiverShape(runtime, null, x, z, surface, minBuildY);
        boolean steep = false;
        BlockState[] states = new BlockState[height.getHeight()];
        for (int i = 0; i < states.length; i++) {
            int y = minBuildY + i;
            states[i] = ChunkTerrainPlanner.columnStateFor(y, river.terrainSurfaceY(), minBuildY, surfaceRock, steep, river, surface, vegetation, seaLevelY);
        }
        return new NoiseColumn(minBuildY, states);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
        info.add("UKGeo surface: " + surfaceY(pos.getX(), pos.getZ()));
    }

    private enum VegetationTreeKind {
        OAK,
        BIRCH,
        SPRUCE
    }

    record RuntimeData(TileManifest manifest, R16HeightTileLayer height, U8OreTileLayer surfaceLayer, U8OreTileLayer vegetationLayer, U8OreTileLayer riverLayer, Map<String, U8OreTileLayer> oreLayers, List<OreDefinition> ores) {
    }

    public record RiverShape(boolean hasWater, boolean influenced, int terrainSurfaceY, int waterSurfaceY) {
        static RiverShape none(int surfaceY) {
            return new RiverShape(false, false, surfaceY, surfaceY);
        }
    }

    private record RiverDistance(double blocks, int score) {
        static RiverDistance none() {
            return new RiverDistance(Double.POSITIVE_INFINITY, 0);
        }

        boolean found() {
            return score > 0;
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
