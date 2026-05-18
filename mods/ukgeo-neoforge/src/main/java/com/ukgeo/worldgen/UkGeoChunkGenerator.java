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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
    private static final int WATER_EDGE_SMOOTHING_RADIUS = 16;
    private static final int WATER_EDGE_SAMPLE_STEP = 1;
    private static final int WATER_EDGE_MAX_LAND_HEIGHT_ABOVE_SEA = 24;
    private static final int SHALLOW_WATER_DEPTH = 2;
    private static final double BACKGROUND_ORE_ATTEMPT_MULTIPLIER = 0.1;
    private static final double ORE_AREA_ATTEMPT_MULTIPLIER = 3.0;
    private static volatile boolean createDieselGeneratorsOilLookupAttempted;
    private static volatile Method createDieselGeneratorsSetOilAmount;

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
        Codec.INT.optionalFieldOf("min_y", -256).forGetter(generator -> generator.minY),
        Codec.INT.optionalFieldOf("gen_depth", 2288).forGetter(generator -> generator.genDepth),
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
        super(biomeSource, UkGeoChunkGenerator::withoutVanillaOres);
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

    private static BiomeGenerationSettings withoutVanillaOres(Holder<Biome> biome) {
        List<HolderSet<PlacedFeature>> features = new ArrayList<>(biome.value().getGenerationSettings().features());
        int oreStep = GenerationStep.Decoration.UNDERGROUND_ORES.ordinal();
        if (features.size() > oreStep) {
            features.set(oreStep, HolderSet.direct(List.<Holder<PlacedFeature>>of()));
        }
        return new BiomeGenerationSettings(Map.of(), List.copyOf(features));
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunk) {
        return CompletableFuture.supplyAsync(Util.wrapThreadWithTaskName("ukgeo_fill", () -> {
            fillTerrain(chunk);
            placeOres(chunk);
            return chunk;
        }), Util.backgroundExecutor());
    }

    private void fillTerrain(ChunkAccess chunk) {
        ChunkPos pos = chunk.getPos();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        Heightmap ocean = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        int maxY = chunk.getMaxBuildHeight() - 1;
        int minBuildY = chunk.getMinBuildHeight();
        for (int localX = 0; localX < 16; localX++) {
            int worldX = pos.getMinBlockX() + localX;
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldZ = pos.getMinBlockZ() + localZ;
                int top = Math.clamp(surfaceY(worldX, worldZ), minBuildY + 1, maxY);
                RiverShape river = riverShape(worldX, worldZ, top, minBuildY);
                int terrainTop = river.terrainSurfaceY();
                BlockState surfaceRock = surfaceGeologyBlock(worldX, worldZ, top);
                boolean steep = isSteepSurface(worldX, worldZ, top);
                int columnTop = Math.max(Math.max(top, seaLevelY), Math.max(terrainTop + 1, river.waterSurfaceY()));
                for (int y = minBuildY; y <= columnTop; y++) {
                    BlockState state = stateFor(worldX, worldZ, y, terrainTop, minBuildY, surfaceRock, steep, river, top);
                    chunk.setBlockState(cursor.set(localX, y, localZ), state, false);
                    ocean.update(localX, y, localZ, state);
                    surface.update(localX, y, localZ, state);
                }
            }
        }
    }

    private BlockState stateFor(int x, int z, int y, int surfaceY, int minBuildY, BlockState surfaceRock, boolean steep, RiverShape river, int originalSurfaceY) {
        if (y == minBuildY) {
            return Blocks.BEDROCK.defaultBlockState();
        }
        if (river.hasWater() && y > surfaceY && y <= river.waterSurfaceY()) {
            return Blocks.WATER.defaultBlockState();
        }
        if (river.hasWater() && y > river.waterSurfaceY() && y <= originalSurfaceY) {
            return y <= seaLevelY ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }
        if (y > surfaceY) {
            return y <= seaLevelY ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }
        if (river.influenced() && y >= surfaceY - 1) {
            return Blocks.GRAVEL.defaultBlockState();
        }
        if (surfaceY <= seaLevelY + 2 && y >= surfaceY - 2) {
            return Blocks.SAND.defaultBlockState();
        }
        if (steep && y >= surfaceY - 4) {
            return surfaceRock;
        }
        if (y == surfaceY) {
            return Blocks.GRASS_BLOCK.defaultBlockState();
        }
        if (y >= surfaceY - 3) {
            return Blocks.DIRT.defaultBlockState();
        }
        if (y >= surfaceY - 12) {
            return surfaceRock;
        }
        return y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }

    private int riverBedY(int surfaceY, int minBuildY, int depth) {
        return Math.max(minBuildY + 1, surfaceY - depth);
    }

    private int riverWaterSurfaceY(int x, int z, int originalSurfaceY, int riverBedY) {
        int smoothedSurface = smoothedSurfaceY(x, z, Math.max(2, riverWidenRadius + 2)).orElse(originalSurfaceY);
        int target = Math.round((originalSurfaceY * 0.45f) + (smoothedSurface * 0.55f)) - 1;
        return Math.clamp(target, riverBedY + 1, originalSurfaceY - 1);
    }

    private boolean isSteepSurface(int x, int z, int surfaceY) {
        int max = surfaceY;
        int min = surfaceY;
        int step = 4;
        int h1 = surfaceY(x + step, z);
        int h2 = surfaceY(x - step, z);
        int h3 = surfaceY(x, z + step);
        int h4 = surfaceY(x, z - step);
        max = Math.max(max, Math.max(Math.max(h1, h2), Math.max(h3, h4)));
        min = Math.min(min, Math.min(Math.min(h1, h2), Math.min(h3, h4)));
        return max - min >= 6;
    }

    private RiverShape riverShape(int x, int z, int originalSurfaceY, int minBuildY) {
        RuntimeData data = data();
        if (data == null || data.riverLayer == null) {
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
            return new RiverShape(true, true, bed, riverWaterSurfaceY(x, z, originalSurfaceY, bed));
        }
        int bankDrop = riverBankDrop(distance.blocks(), waterRadius, bankRadius);
        if (bankDrop <= 0) {
            return RiverShape.none(originalSurfaceY);
        }
        int terrainSurfaceY = Math.max(minBuildY + 1, originalSurfaceY - bankDrop);
        return new RiverShape(false, true, terrainSurfaceY, terrainSurfaceY);
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
        if (data != null) {
            OptionalInt decimetres = data.height.sampleDecimetres(x, z);
            if (decimetres.isPresent()) {
                int rawSurfaceY = rawSurfaceY(x, z, decimetres.getAsInt());
                return waterSmoothedSurfaceY(data, x, z, decimetres.getAsInt(), rawSurfaceY);
            }
            return waterFloorY(data, x, z, nodataSurfaceY);
        }
        return fallbackHeight;
    }

    private int rawSurfaceY(int x, int z, int decimetres) {
        return seaLevelY + Math.round((float) shapedHeightMetres(x, z, decimetres / 10.0));
    }

    private int waterSmoothedSurfaceY(RuntimeData data, int x, int z, int decimetres, int rawSurfaceY) {
        // Treat nodata and non-positive terrain as water, then blend nearby low land into a shallow shore.
        if (decimetres <= 0) {
            return waterFloorY(data, x, z, Math.min(rawSurfaceY, seaLevelY - SHALLOW_WATER_DEPTH));
        }
        int heightAboveSea = rawSurfaceY - seaLevelY;
        if (heightAboveSea <= 0 || heightAboveSea > WATER_EDGE_MAX_LAND_HEIGHT_ABOVE_SEA) {
            return rawSurfaceY;
        }
        Optional<WaterDistance> nearestWater = nearestHeightWater(data, x, z, WATER_EDGE_SMOOTHING_RADIUS);
        if (nearestWater.isEmpty()) {
            return rawSurfaceY;
        }
        double shoreWeight = 1.0 - smoothstep(nearestWater.get().blocks() / WATER_EDGE_SMOOTHING_RADIUS);
        double lowLandWeight = 1.0 - Math.clamp(heightAboveSea / (double) WATER_EDGE_MAX_LAND_HEIGHT_ABOVE_SEA, 0.0, 1.0);
        double amount = shoreWeight * lowLandWeight;
        int shoreSurfaceY = seaLevelY + 1;
        return Math.round((float) lerp(rawSurfaceY, shoreSurfaceY, amount));
    }

    private int waterFloorY(RuntimeData data, int x, int z, int deepFloorY) {
        Optional<LandDistance> nearestLand = nearestHeightLand(data, x, z, WATER_EDGE_SMOOTHING_RADIUS);
        if (nearestLand.isEmpty()) {
            return deepFloorY;
        }
        double deepWaterWeight = smoothstep(nearestLand.get().blocks() / WATER_EDGE_SMOOTHING_RADIUS);
        int shoreFloorY = seaLevelY - SHALLOW_WATER_DEPTH;
        return Math.round((float) lerp(shoreFloorY, deepFloorY, deepWaterWeight));
    }

    private Optional<WaterDistance> nearestHeightWater(RuntimeData data, int x, int z, int radius) {
        double bestDistance = Double.POSITIVE_INFINITY;
        int step = Math.max(1, WATER_EDGE_SAMPLE_STEP);
        for (int dz = -radius; dz <= radius; dz += step) {
            for (int dx = -radius; dx <= radius; dx += step) {
                int distanceSquared = dx * dx + dz * dz;
                if (distanceSquared > radius * radius) {
                    continue;
                }
                OptionalInt sample = data.height.sampleDecimetres(x + dx, z + dz);
                if (sample.isPresent() && sample.getAsInt() > 0) {
                    continue;
                }
                bestDistance = Math.min(bestDistance, Math.sqrt(distanceSquared));
            }
        }
        return Double.isInfinite(bestDistance) ? Optional.empty() : Optional.of(new WaterDistance(bestDistance));
    }

    private Optional<LandDistance> nearestHeightLand(RuntimeData data, int x, int z, int radius) {
        double bestDistance = Double.POSITIVE_INFINITY;
        int step = Math.max(1, WATER_EDGE_SAMPLE_STEP);
        for (int dz = -radius; dz <= radius; dz += step) {
            for (int dx = -radius; dx <= radius; dx += step) {
                int distanceSquared = dx * dx + dz * dz;
                if (distanceSquared > radius * radius) {
                    continue;
                }
                OptionalInt sample = data.height.sampleDecimetres(x + dx, z + dz);
                if (sample.isEmpty() || sample.getAsInt() <= 0) {
                    continue;
                }
                double distance = Math.sqrt(distanceSquared);
                if (distance < bestDistance) {
                    bestDistance = distance;
                }
            }
        }
        return Double.isInfinite(bestDistance) ? Optional.empty() : Optional.of(new LandDistance(bestDistance));
    }

    private double shapedHeightMetres(int x, int z, double metres) {
        double highlandWeight = smoothstep((metres - highlandStartMetres) / (highlandFullMetres - highlandStartMetres));
        if (highlandWeight > 0.0 && highlandSmoothingRadius > 0) {
            double smoothed = smoothedMetres(x, z, highlandSmoothingRadius).orElse(metres);
            metres = lerp(metres, smoothed, highlandWeight);
        }
        double lowlandWeight = metres <= 0.0 ? 1.0 : 1.0 - Math.clamp(metres / lowlandCeilingMetres, 0.0, 1.0);
        double scale = heightScale + lowlandExtraScale * lowlandWeight;
        scale = lerp(scale, highlandScale, highlandWeight);
        return metres * scale;
    }

    private Optional<Double> smoothedMetres(int x, int z, int radius) {
        RuntimeData data = data();
        if (data == null) {
            return Optional.empty();
        }
        double total = 0.0;
        int count = 0;
        int step = Math.max(1, radius / 2);
        for (int dz = -radius; dz <= radius; dz += step) {
            for (int dx = -radius; dx <= radius; dx += step) {
                OptionalInt sample = data.height.sampleDecimetres(x + dx, z + dz);
                if (sample.isPresent()) {
                    total += sample.getAsInt() / 10.0;
                    count++;
                }
            }
        }
        return count == 0 ? Optional.empty() : Optional.of(total / count);
    }

    private OptionalInt smoothedSurfaceY(int x, int z, int radius) {
        RuntimeData data = data();
        if (data == null) {
            return OptionalInt.empty();
        }
        int total = 0;
        int count = 0;
        int step = Math.max(1, radius / 2);
        for (int dz = -radius; dz <= radius; dz += step) {
            for (int dx = -radius; dx <= radius; dx += step) {
                OptionalInt sample = data.height.sampleDecimetres(x + dx, z + dz);
                if (sample.isPresent()) {
                    total += seaLevelY + Math.round((float) shapedHeightMetres(x + dx, z + dz, sample.getAsInt() / 10.0));
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

    private void placeOres(ChunkAccess chunk) {
        RuntimeData data = data();
        if (data == null) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        long seed = (((long) pos.x) << 32) ^ (pos.z & 0xffffffffL) ^ 0x554b47454f4cL;
        java.util.Random random = new java.util.Random(seed);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (OreDefinition ore : data.ores) {
            U8OreTileLayer scoreLayer = ore.hasScoreLayer() ? data.oreLayers.get(ore.scoreLayer()) : null;
            Optional<BlockStatePair> states = resolveOreBlocks(ore);
            if (states.isEmpty()) {
                continue;
            }
            int score = scoreLayer == null ? 0 : scoreLayer.sample(pos.getMinBlockX() + 8, pos.getMinBlockZ() + 8).orElse(0);
            int normalAttempts = normalOreAttempts(ore, score);
            int attempts = scaledOreAttempts(normalAttempts, score > 0, random);
            for (int attempt = 0; attempt < attempts; attempt++) {
                int localX = random.nextInt(16);
                int localZ = random.nextInt(16);
                int worldX = pos.getMinBlockX() + localX;
                int worldZ = pos.getMinBlockZ() + localZ;
                int top = surfaceY(worldX, worldZ);
                int min = Math.max(chunk.getMinBuildHeight() + 1, top - ore.maxDepthBelowSurface());
                int max = Math.max(min, top - ore.minDepthBelowSurface());
                int y = min + random.nextInt(Math.max(1, max - min + 1));
                for (int i = 0; i < ore.veinSize(); i++) {
                    int px = Math.clamp(localX + random.nextInt(5) - 2, 0, 15);
                    int pz = Math.clamp(localZ + random.nextInt(5) - 2, 0, 15);
                    int py = Math.clamp(y + random.nextInt(5) - 2, chunk.getMinBuildHeight() + 1, chunk.getMaxBuildHeight() - 1);
                    BlockState current = chunk.getBlockState(cursor.set(px, py, pz));
                    if (current.is(Blocks.STONE) || current.is(Blocks.DEEPSLATE)) {
                        chunk.setBlockState(cursor, py < 0 ? states.get().deepslate : states.get().normal, false);
                    }
                }
            }
        }
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
                U8OreTileLayer riverLayer = manifest.riversPath == null ? null : new U8OreTileLayer(manifest, "rivers", manifest.riversPath);
                runtimeData = new RuntimeData(manifest, new R16HeightTileLayer(manifest), surfaceLayer, riverLayer, layers, OreSettings.defaults());
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
        return "tiles=%dx%d tileSize=%d heightScale=%.3f lowExtra=%.3f highScale=%.3f nodataY=%d riverRadius=%d riverDepth=%d heightCache=%s".formatted(
            data.manifest.tilesX(),
            data.manifest.tilesZ(),
            data.manifest.tileSize,
            heightScale,
            lowlandExtraScale,
            highlandScale,
            nodataSurfaceY,
            riverWidenRadius,
            riverCarveDepth,
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

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureManager, RandomState random, ChunkAccess chunk) {
        scheduleWaterTicks(level, chunk);
        populateCreateDieselGeneratorsOil(level.getLevel(), chunk.getPos());
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
                RiverShape river = riverShape(worldX, worldZ, top, minBuildY);
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
        RiverShape river = riverShape(x, z, surface, level.getMinBuildHeight());
        int top = river.hasWater() ? river.waterSurfaceY() : river.terrainSurfaceY();
        return Math.clamp(top + 1, level.getMinBuildHeight(), level.getMaxBuildHeight());
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState random) {
        int surface = surfaceY(x, z);
        BlockState surfaceRock = surfaceGeologyBlock(x, z, surface);
        boolean steep = isSteepSurface(x, z, surface);
        RiverShape river = riverShape(x, z, surface, height.getMinBuildHeight());
        BlockState[] states = new BlockState[height.getHeight()];
        for (int i = 0; i < states.length; i++) {
            int y = height.getMinBuildHeight() + i;
            states[i] = stateFor(x, z, y, river.terrainSurfaceY(), height.getMinBuildHeight(), surfaceRock, steep, river, surface);
        }
        return new NoiseColumn(height.getMinBuildHeight(), states);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
        info.add("UKGeo surface: " + surfaceY(pos.getX(), pos.getZ()));
    }

    private record RuntimeData(TileManifest manifest, R16HeightTileLayer height, U8OreTileLayer surfaceLayer, U8OreTileLayer riverLayer, Map<String, U8OreTileLayer> oreLayers, List<OreDefinition> ores) {
    }

    private record RiverShape(boolean hasWater, boolean influenced, int terrainSurfaceY, int waterSurfaceY) {
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
