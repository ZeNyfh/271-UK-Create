package com.ukgeo.worldgen;

import java.io.IOException;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Precomputes per-column terrain for one chunk on a background thread, then applies blocks in bulk.
 */
final class ChunkTerrainPlanner {
    private static final int CHUNK_SIZE = 16;
    private static final int BORDER = 4;
    private static final int WATERBED_PROTECTION_DEPTH = 6;
    private static final boolean DEBUG_GEN_TIMINGS = Boolean.getBoolean("ukgeo.debugGenTimings");
    private static final boolean DEBUG_FULL_TIMING_SPAM = Boolean.getBoolean("ukgeo.debugTimingSpam");
    private static final boolean SLOW_PLAN_BREAKDOWN = !Boolean.getBoolean("ukgeo.disableSlowPlanBreakdown");
    private static final long SLOW_PLAN_WARN_MS = Long.getLong("ukgeo.slowPlanWarnMs", 500L);
    private static final boolean DEBUG_CAVES = Boolean.getBoolean("ukgeo.debugCaves");
    private static final boolean FAST_SECTION_WRITES = !Boolean.getBoolean("ukgeo.disableFastSectionWrites");
    private static final int DEBUG_CAVE_X = Integer.getInteger("ukgeo.debugCaveX", 0);
    private static final int DEBUG_CAVE_Z = Integer.getInteger("ukgeo.debugCaveZ", 0);
    private static final int DEBUG_CAVE_RADIUS = Integer.getInteger("ukgeo.debugCaveRadius", 0);
    private static final boolean ENABLE_PARALLEL_CHUNK_PLANNING = Boolean.getBoolean("ukgeo.enableParallelChunkPlanning");
    private static final int PARALLEL_CHUNK_PLANNING_THREADS = Math.max(1, Integer.getInteger(
        "ukgeo.parallelChunkPlanningThreads",
        Math.max(1, Runtime.getRuntime().availableProcessors() / 2)
    ));
    private static final int PARALLEL_SURFACE_GRID_THRESHOLD = Integer.getInteger("ukgeo.parallelSurfaceGridThreshold", 256);
    private static final int PARALLEL_COLUMN_THRESHOLD = Integer.getInteger("ukgeo.parallelColumnThreshold", 128);
    private static final int MINERAL_DEPOSIT_SURFACE_DEPTH = Math.max(12, Integer.getInteger("ukgeo.mineralDepositSurfaceDepth", 72));
    private static final ForkJoinPool PLANNING_POOL = new ForkJoinPool(PARALLEL_CHUNK_PLANNING_THREADS);
    private static final AtomicBoolean PARALLEL_MODE_LOGGED = new AtomicBoolean();

    private ChunkTerrainPlanner() {
    }

    static Plan compute(UkGeoChunkGenerator generator, UkGeoChunkGenerator.RuntimeData data, ChunkAccess chunk) throws IOException {
        long startNanos = System.nanoTime();
        int chunkMinX = chunk.getPos().getMinBlockX();
        int chunkMinZ = chunk.getPos().getMinBlockZ();
        int minBuildY = chunk.getMinBuildHeight();
        int maxBuildY = chunk.getMaxBuildHeight() - 1;
        int margin = Math.max(generator.sampleMargin(), BORDER);
        long windowStartNanos = System.nanoTime();
        HeightTileWindow heightWindow = HeightTileWindow.forChunk(data.height(), data.manifest(), chunkMinX, chunkMinZ, margin);
        long windowElapsedNanos = System.nanoTime() - windowStartNanos;
        logTiming("HeightTileWindow.forChunk", chunk, windowStartNanos);

        logParallelModeOnce();
        UkGeoChunkGenerator.RiverSearchIndex riverSearchIndex = generator.buildRiverSearchIndex(data, chunkMinX, chunkMinZ);

        long surfaceStartNanos = System.nanoTime();
        int gridSize = CHUNK_SIZE + BORDER * 2;
        int[] surfaceGrid = new int[gridSize * gridSize];
        forEachPlanningCell(gridSize * gridSize, PARALLEL_SURFACE_GRID_THRESHOLD, index -> {
            int gz = index / gridSize;
            int gx = index - gz * gridSize;
            int worldX = chunkMinX + gx - BORDER;
            int worldZ = chunkMinZ + gz - BORDER;
            surfaceGrid[index] = generator.computeSurfaceY(data, heightWindow, worldX, worldZ);
        });
        long surfaceElapsedNanos = System.nanoTime() - surfaceStartNanos;
        logTiming("ChunkTerrainPlanner.surfaceGrid", chunk, surfaceStartNanos);

        long waterStartNanos = System.nanoTime();
        ColumnPlan[] columns = new ColumnPlan[CHUNK_SIZE * CHUNK_SIZE];
        if (shouldParallelize(CHUNK_SIZE * CHUNK_SIZE, PARALLEL_COLUMN_THRESHOLD)) {
            forEachPlanningCell(CHUNK_SIZE * CHUNK_SIZE, PARALLEL_COLUMN_THRESHOLD, index ->
                planColumn(generator, data, heightWindow, columns, surfaceGrid, gridSize, chunkMinX, chunkMinZ, minBuildY, maxBuildY, index, new UkGeoChunkGenerator.WaterShapeCache(riverSearchIndex))
            );
        } else {
            UkGeoChunkGenerator.WaterShapeCache waterShapeCache = new UkGeoChunkGenerator.WaterShapeCache(riverSearchIndex);
            for (int index = 0; index < CHUNK_SIZE * CHUNK_SIZE; index++) {
                planColumn(generator, data, heightWindow, columns, surfaceGrid, gridSize, chunkMinX, chunkMinZ, minBuildY, maxBuildY, index, waterShapeCache);
            }
        }
        long waterElapsedNanos = System.nanoTime() - waterStartNanos;
        logTiming("ChunkTerrainPlanner.waterPlanning", chunk, waterStartNanos);
        long oreStartNanos = System.nanoTime();
        Plan plan = new Plan(columns, generator.buildOrePlacements(data, chunk, columns), generator.seaLevel());
        long oreElapsedNanos = System.nanoTime() - oreStartNanos;
        logTiming("ChunkTerrainPlanner.orePlanning", chunk, oreStartNanos);
        long totalElapsedNanos = System.nanoTime() - startNanos;
        logTiming("ChunkTerrainPlanner.compute", chunk, startNanos);
        logSlowPlanBreakdown(chunk, windowElapsedNanos, surfaceElapsedNanos, waterElapsedNanos, oreElapsedNanos, totalElapsedNanos);
        return plan;
    }

    private static void logSlowPlanBreakdown(
        ChunkAccess chunk,
        long windowElapsedNanos,
        long surfaceElapsedNanos,
        long waterElapsedNanos,
        long oreElapsedNanos,
        long totalElapsedNanos
    ) {
        if (!SLOW_PLAN_BREAKDOWN || SLOW_PLAN_WARN_MS <= 0L || totalElapsedNanos < SLOW_PLAN_WARN_MS * 1_000_000L) {
            return;
        }
        long accountedNanos = windowElapsedNanos + surfaceElapsedNanos + waterElapsedNanos + oreElapsedNanos;
        long otherNanos = Math.max(0L, totalElapsedNanos - accountedNanos);
        UkGeoMod.LOGGER.warn(
            "UKGeo slow plan breakdown chunk {} heightWindow={}ms surfaceGrid={}ms waterPlanning={}ms orePlanning={}ms other={}ms total={}ms",
            chunk.getPos(),
            nanosToMillis(windowElapsedNanos),
            nanosToMillis(surfaceElapsedNanos),
            nanosToMillis(waterElapsedNanos),
            nanosToMillis(oreElapsedNanos),
            nanosToMillis(otherNanos),
            nanosToMillis(totalElapsedNanos)
        );
    }


    private static void planColumn(
        UkGeoChunkGenerator generator,
        UkGeoChunkGenerator.RuntimeData data,
        HeightTileWindow heightWindow,
        ColumnPlan[] columns,
        int[] surfaceGrid,
        int gridSize,
        int chunkMinX,
        int chunkMinZ,
        int minBuildY,
        int maxBuildY,
        int index,
        UkGeoChunkGenerator.WaterShapeCache waterShapeCache
    ) {
        int localZ = index / CHUNK_SIZE;
        int localX = index - localZ * CHUNK_SIZE;
        int worldX = chunkMinX + localX;
        int worldZ = chunkMinZ + localZ;
        int surfaceY = surfaceGrid[(localZ + BORDER) * gridSize + (localX + BORDER)];
        boolean hasHeightData = generator.hasHeightData(data, heightWindow, worldX, worldZ);
        boolean steep = isSteep(surfaceGrid, gridSize, localX + BORDER, localZ + BORDER);
        boolean coastalBeach = isCoastalBeach(surfaceGrid, gridSize, localX + BORDER, localZ + BORDER, generator.seaLevel());
        int vegetationClass = hasHeightData ? generator.sampleVegetationClass(data, worldX, worldZ) : 0;
        UkGeoChunkGenerator.RiverShape river = hasHeightData
            ? generator.computeSurfaceWaterShape(data, heightWindow, worldX, worldZ, surfaceY, minBuildY, vegetationClass, waterShapeCache)
            : UkGeoChunkGenerator.RiverShape.none(surfaceY);
        int terrainTop = river.terrainSurfaceY();
        int top = Math.clamp(surfaceY, minBuildY + 1, maxBuildY);
        int columnTop = Math.clamp(
            Math.max(Math.max(top, generator.seaLevel()), Math.max(terrainTop + 1, river.waterSurfaceY())),
            minBuildY,
            maxBuildY
        );
        BlockState surfaceRock = hasHeightData ? generator.sampleSurfaceRock(data, worldX, worldZ, terrainTop) : generator.defaultBaseRock(terrainTop);
        BlockState exposedSurfaceRock = exposedSurfaceRock(worldX, worldZ, surfaceRock);
        columns[index] = new ColumnPlan(top, terrainTop, columnTop, steep, coastalBeach, river, vegetationClass, surfaceRock, exposedSurfaceRock, hasHeightData);
    }

    private static boolean shouldParallelize(int cells, int threshold) {
        return ENABLE_PARALLEL_CHUNK_PLANNING && PARALLEL_CHUNK_PLANNING_THREADS > 1 && cells >= threshold;
    }

    private static void forEachPlanningCell(int cells, int threshold, IntConsumer action) {
        if (!shouldParallelize(cells, threshold)) {
            for (int i = 0; i < cells; i++) {
                action.accept(i);
            }
            return;
        }
        PLANNING_POOL.submit(() -> IntStream.range(0, cells).parallel().forEach(action)).join();
    }

    private static void logParallelModeOnce() {
        if (PARALLEL_MODE_LOGGED.compareAndSet(false, true)) {
            UkGeoMod.LOGGER.info(
                "UKGeo planner parallelism: enabled={} threads={} surfaceThreshold={} columnThreshold={}",
                ENABLE_PARALLEL_CHUNK_PLANNING,
                PARALLEL_CHUNK_PLANNING_THREADS,
                PARALLEL_SURFACE_GRID_THRESHOLD,
                PARALLEL_COLUMN_THRESHOLD
            );
        }
    }

    static void apply(Plan plan, ChunkAccess chunk, CaveMask caveMask) {
        long startNanos = System.nanoTime();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minBuildY = chunk.getMinBuildHeight();
        int chunkMinX = chunk.getPos().getMinBlockX();
        int chunkMinZ = chunk.getPos().getMinBlockZ();
        WaterProtection[] waterProtections = waterProtections(plan);
        long fillNanos = 0L;
        for (int localZ = 0; localZ < CHUNK_SIZE; localZ++) {
            for (int localX = 0; localX < CHUNK_SIZE; localX++) {
                ColumnPlan column = plan.columns[localZ * CHUNK_SIZE + localX];
                WaterProtection protection = waterProtections[localZ * CHUNK_SIZE + localX];
                int vanillaTop = caveMask.usesDelegate() ? reliableDelegateTop(chunk, cursor, localX, localZ, caveMask.delegateMinY(), caveMask.delegateMaxY()) : minBuildY;
                long fillStartNanos = DEBUG_GEN_TIMINGS ? System.nanoTime() : 0L;
                fillColumn(
                    chunk,
                    cursor,
                    localX,
                    localZ,
                    chunkMinX + localX,
                    chunkMinZ + localZ,
                    minBuildY,
                    column,
                    protection,
                    plan.seaLevelY(),
                    caveMask,
                    vanillaTop
                );
                if (DEBUG_GEN_TIMINGS) {
                    fillNanos += System.nanoTime() - fillStartNanos;
                }
            }
        }
        if (DEBUG_GEN_TIMINGS && DEBUG_FULL_TIMING_SPAM) {
            UkGeoMod.LOGGER.info("UKGeo timing chunk {} ChunkTerrainPlanner.fillColumn total={}ms", chunk.getPos(), nanosToMillis(fillNanos));
        }
        // Ores/mineral deposits must be part of the base terrain, not a late surface/decoration pass.
        // Placing them here means later carvers and structures can cut through or overwrite them,
        // preventing mineral blocks from appearing in the air inside generated structures.
        applyOres(plan, chunk);
        chunk.setUnsaved(true);
        logTiming("ChunkTerrainPlanner.apply", chunk, startNanos);
    }

    private static int reliableDelegateTop(
        ChunkAccess chunk,
        BlockPos.MutableBlockPos cursor,
        int localX,
        int localZ,
        int delegateMinY,
        int delegateMaxY
    ) {
        for (int y = delegateMaxY; y >= delegateMinY; y--) {
            BlockState state = chunk.getBlockState(cursor.set(localX, y, localZ));
            if (!state.isAir() && !state.is(Blocks.WATER) && !state.is(Blocks.LAVA)) {
                return y;
            }
        }
        return delegateMinY - 1;
    }

    static void enforceWaterColumns(Plan plan, ChunkAccess chunk) {
        long startNanos = System.nanoTime();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minBuildY = chunk.getMinBuildHeight();
        for (int localZ = 0; localZ < CHUNK_SIZE; localZ++) {
            for (int localX = 0; localX < CHUNK_SIZE; localX++) {
                ColumnPlan column = plan.columns[localZ * CHUNK_SIZE + localX];
                int waterSurfaceY = plannedWaterSurfaceY(column, plan.seaLevelY());
                if (waterSurfaceY == Integer.MIN_VALUE) {
                    clearUnplannedSurfaceWater(chunk, cursor, localX, localZ, column.terrainTop());
                    continue;
                }
                for (int y = Math.max(minBuildY, column.terrainTop()); y <= waterSurfaceY; y++) {
                    BlockState state = enforcedWaterColumnState(y, column, minBuildY, plan.seaLevelY());
                    setBlock(chunk, cursor, localX, y, localZ, state);
                }
                clearWaterAboveSurface(chunk, cursor, localX, localZ, waterSurfaceY);
            }
        }
        logTiming("ChunkTerrainPlanner.enforceWaterColumns", chunk, startNanos);
    }

    private static void clearUnplannedSurfaceWater(ChunkAccess chunk, BlockPos.MutableBlockPos cursor, int localX, int localZ, int terrainTop) {
        int fromY = Math.max(chunk.getMinBuildHeight(), terrainTop + 1);
        int toY = Math.min(chunk.getMaxBuildHeight() - 1, terrainTop + 4);
        for (int y = fromY; y <= toY; y++) {
            if (chunk.getBlockState(cursor.set(localX, y, localZ)).is(Blocks.WATER)) {
                setBlock(chunk, cursor, localX, y, localZ, Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static void clearWaterAboveSurface(ChunkAccess chunk, BlockPos.MutableBlockPos cursor, int localX, int localZ, int waterSurfaceY) {
        int fromY = Math.max(chunk.getMinBuildHeight(), waterSurfaceY + 1);
        int toY = Math.min(chunk.getMaxBuildHeight() - 1, waterSurfaceY + 3);
        for (int y = fromY; y <= toY; y++) {
            if (chunk.getBlockState(cursor.set(localX, y, localZ)).is(Blocks.WATER)) {
                setBlock(chunk, cursor, localX, y, localZ, Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static BlockState enforcedWaterColumnState(int y, ColumnPlan column, int minBuildY, int seaLevelY) {
        int floorY = column.terrainTop();
        if (y > floorY) {
            return Blocks.WATER.defaultBlockState();
        }
        return columnStateFor(
            y,
            floorY,
            minBuildY,
            column.surfaceRock(),
            column.exposedSurfaceRock(),
            column.steep(),
            column.river(),
            column.originalSurfaceY(),
            column.coastalBeach(),
            column.vegetationClass(),
            seaLevelY
        );
    }

    static void applyOres(Plan plan, ChunkAccess chunk) {
        long startNanos = System.nanoTime();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (OrePlacement placement : plan.orePlacements) {
            ColumnPlan column = plan.columns[placement.localZ() * CHUNK_SIZE + placement.localX()];
            if (isProtectedWaterColumn(column, placement.y(), plan.seaLevelY())) {
                continue;
            }
            BlockState current = chunk.getBlockState(cursor.set(placement.localX(), placement.y(), placement.localZ()));
            if (current.is(Blocks.STONE) || current.is(Blocks.DEEPSLATE)) {
                setBlock(chunk, cursor, placement.localX(), placement.y(), placement.localZ(), placement.state());
            }
        }
        logTiming("ChunkTerrainPlanner.applyOres", chunk, startNanos);
    }

    private static boolean isSteep(int[] surfaceGrid, int gridSize, int gx, int gz) {
        int center = surfaceGrid[gz * gridSize + gx];
        int max = center;
        int min = center;
        int step = 4;
        int[] offsets = {step, 0, -step, 0, 0, step, 0, -step};
        for (int i = 0; i < offsets.length; i += 2) {
            int nx = gx + offsets[i];
            int nz = gz + offsets[i + 1];
            if (nx < 0 || nz < 0 || nx >= gridSize || nz >= gridSize) {
                continue;
            }
            int sample = surfaceGrid[nz * gridSize + nx];
            max = Math.max(max, sample);
            min = Math.min(min, sample);
        }
        return max - min >= 6;
    }

    private static boolean isCoastalBeach(int[] surfaceGrid, int gridSize, int gx, int gz, int seaLevelY) {
        int surfaceY = surfaceGrid[gz * gridSize + gx];
        if (surfaceY < seaLevelY || surfaceY > seaLevelY + 2) {
            return false;
        }
        int radius = 3;
        int radiusSquared = radius * radius;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int nx = gx + dx;
                int nz = gz + dz;
                if (nx < 0 || nz < 0 || nx >= gridSize || nz >= gridSize || dx * dx + dz * dz > radiusSquared) {
                    continue;
                }
                if (surfaceGrid[nz * gridSize + nx] < seaLevelY) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double smoothstep(double value) {
        double t = Math.clamp(value, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static void fillColumn(
        ChunkAccess chunk,
        BlockPos.MutableBlockPos cursor,
        int localX,
        int localZ,
        int worldX,
        int worldZ,
        int minBuildY,
        ColumnPlan column,
        WaterProtection protection,
        int seaLevelY,
        CaveMask caveMask,
        int vanillaTop
    ) {
        int terrainTop = column.terrainTop();
        int columnTop = column.columnTop();
        int clearTop = caveMask.usesDelegate() ? Math.max(columnTop, Math.min(vanillaTop, caveMask.delegateMaxY())) : columnTop;
        UkGeoChunkGenerator.RiverShape river = column.river();
        BlockState surfaceRock = column.surfaceRock();
        BlockState exposedSurfaceRock = column.exposedSurfaceRock();
        boolean steep = column.steep();
        int originalSurfaceY = column.originalSurfaceY();
        boolean coastalBeach = column.coastalBeach();
        int vegetationClass = column.vegetationClass();

        setBlock(chunk, cursor, localX, minBuildY, localZ, Blocks.BEDROCK.defaultBlockState());
        int stoneTop = Math.max(minBuildY + 1, terrainTop - 12);
        for (int y = minBuildY + 1; y < stoneTop; y++) {
            CaveState caveState = y <= terrainTop && caveMask.mayCarveAtY(y, vanillaTop)
                ? caveState(chunk, cursor, caveMask, localX, y, localZ, worldX, worldZ, vanillaTop, terrainTop)
                : CaveState.SOLID;
            if (caveState == CaveState.AIR) {
                if (isProtectedWaterCave(protection, y)) {
                    setBlock(chunk, cursor, localX, y, localZ, protectedWaterState(column, protection, y, minBuildY, surfaceRock, steep, river, originalSurfaceY, vegetationClass, seaLevelY));
                    continue;
                }
                setBlock(chunk, cursor, localX, y, localZ, Blocks.AIR.defaultBlockState());
                continue;
            } else if (caveState == CaveState.LAVA) {
                if (isProtectedWaterCave(protection, y)) {
                    setBlock(chunk, cursor, localX, y, localZ, protectedWaterState(column, protection, y, minBuildY, surfaceRock, steep, river, originalSurfaceY, vegetationClass, seaLevelY));
                    continue;
                }
                setBlock(chunk, cursor, localX, y, localZ, Blocks.LAVA.defaultBlockState());
                continue;
            }
            setBlock(chunk, cursor, localX, y, localZ, y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState());
        }
        for (int y = stoneTop; y <= clearTop; y++) {
            CaveState caveState = y <= terrainTop && caveMask.mayCarveAtY(y, vanillaTop)
                ? caveState(chunk, cursor, caveMask, localX, y, localZ, worldX, worldZ, vanillaTop, terrainTop)
                : CaveState.SOLID;
            if (caveState == CaveState.AIR) {
                if (isProtectedWaterCave(protection, y)) {
                    setBlock(chunk, cursor, localX, y, localZ, protectedWaterState(column, protection, y, minBuildY, surfaceRock, steep, river, originalSurfaceY, vegetationClass, seaLevelY));
                    continue;
                }
                setBlock(chunk, cursor, localX, y, localZ, Blocks.AIR.defaultBlockState());
                continue;
            } else if (caveState == CaveState.LAVA) {
                if (isProtectedWaterCave(protection, y)) {
                    setBlock(chunk, cursor, localX, y, localZ, protectedWaterState(column, protection, y, minBuildY, surfaceRock, steep, river, originalSurfaceY, vegetationClass, seaLevelY));
                    continue;
                }
                setBlock(chunk, cursor, localX, y, localZ, Blocks.LAVA.defaultBlockState());
                continue;
            }
            BlockState state = columnStateFor(y, terrainTop, minBuildY, surfaceRock, exposedSurfaceRock, steep, river, originalSurfaceY, coastalBeach, vegetationClass, seaLevelY);
            setBlock(chunk, cursor, localX, y, localZ, state);
        }
    }

    private static boolean isProtectedWaterCave(WaterProtection protection, int y) {
        return protection.hasWater() && y <= protection.waterSurfaceY() && y >= protection.floorY() - WATERBED_PROTECTION_DEPTH;
    }

    private static boolean isProtectedWaterColumn(ColumnPlan column, int y, int seaLevelY) {
        int waterSurfaceY = plannedWaterSurfaceY(column, seaLevelY);
        if (waterSurfaceY == Integer.MIN_VALUE || y > waterSurfaceY) {
            return false;
        }
        int floorY = column.terrainTop();
        return y >= floorY - WATERBED_PROTECTION_DEPTH;
    }

    private static BlockState protectedWaterState(
        ColumnPlan column,
        WaterProtection protection,
        int y,
        int minBuildY,
        BlockState surfaceRock,
        boolean steep,
        UkGeoChunkGenerator.RiverShape river,
        int originalSurfaceY,
        int vegetationClass,
        int seaLevelY
    ) {
        BlockState planned = columnStateFor(y, column.terrainTop(), minBuildY, surfaceRock, column.exposedSurfaceRock(), steep, river, originalSurfaceY, column.coastalBeach(), vegetationClass, seaLevelY);
        if (!planned.isAir()) {
            return planned;
        }
        return y > protection.floorY() && y <= protection.waterSurfaceY() ? Blocks.WATER.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }

    private static boolean isPlannedWaterVolume(ColumnPlan column, int y, int seaLevelY) {
        int waterSurfaceY = plannedWaterSurfaceY(column, seaLevelY);
        return waterSurfaceY != Integer.MIN_VALUE && y > column.terrainTop() && y <= waterSurfaceY;
    }

    private static int plannedWaterSurfaceY(ColumnPlan column, int seaLevelY) {
        if (column.river().hasWater()) {
            return column.river().waterSurfaceY();
        }
        if (column.originalSurfaceY() < seaLevelY) {
            return seaLevelY;
        }
        return Integer.MIN_VALUE;
    }

    private static WaterProtection[] waterProtections(Plan plan) {
        WaterProtection[] protections = new WaterProtection[CHUNK_SIZE * CHUNK_SIZE];
        for (int localZ = 0; localZ < CHUNK_SIZE; localZ++) {
            for (int localX = 0; localX < CHUNK_SIZE; localX++) {
                ColumnPlan column = plan.columns[localZ * CHUNK_SIZE + localX];
                int ownSurface = plannedWaterSurfaceY(column, plan.seaLevelY());
                boolean foundWater = ownSurface != Integer.MIN_VALUE;
                int waterSurfaceY = foundWater ? ownSurface : Integer.MIN_VALUE;
                int floorY = foundWater ? column.terrainTop() : Integer.MIN_VALUE;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = localX + dx;
                        int nz = localZ + dz;
                        if (nx < 0 || nz < 0 || nx >= CHUNK_SIZE || nz >= CHUNK_SIZE) {
                            continue;
                        }
                        ColumnPlan neighbor = plan.columns[nz * CHUNK_SIZE + nx];
                        int neighborSurface = plannedWaterSurfaceY(neighbor, plan.seaLevelY());
                        if (neighborSurface == Integer.MIN_VALUE) {
                            continue;
                        }
                        foundWater = true;
                        waterSurfaceY = Math.max(waterSurfaceY, neighborSurface);
                        floorY = Math.max(floorY, neighbor.terrainTop());
                    }
                }
                protections[localZ * CHUNK_SIZE + localX] = foundWater ? new WaterProtection(true, waterSurfaceY, floorY) : WaterProtection.none();
            }
        }
        return protections;
    }

    private static CaveState caveState(
        ChunkAccess chunk,
        BlockPos.MutableBlockPos cursor,
        CaveMask caveMask,
        int localX,
        int y,
        int localZ,
        int worldX,
        int worldZ,
        int vanillaTop,
        int terrainTop
    ) {
        if (y > terrainTop) {
            return CaveState.SOLID;
        }
        if (!caveMask.usesDelegate()) {
            debugCave(worldX, y, worldZ, terrainTop, vanillaTop, caveMask, false);
            return CaveState.SOLID;
        }
        boolean delegateCave = false;
        if (caveMask.canSampleDelegate(y) && y <= vanillaTop) {
            BlockState existing = chunk.getBlockState(cursor.set(localX, y, localZ));
            delegateCave = existing.isAir() || existing.is(Blocks.LAVA);
        }
        debugCave(worldX, y, worldZ, terrainTop, vanillaTop, caveMask, delegateCave);
        return delegateCave ? CaveState.AIR : CaveState.SOLID;
    }

    private static void debugCave(
        int worldX,
        int y,
        int worldZ,
        int terrainTop,
        int vanillaTop,
        CaveMask caveMask,
        boolean delegateCave
    ) {
        if (!DEBUG_CAVES || Math.abs(worldX - DEBUG_CAVE_X) > DEBUG_CAVE_RADIUS || Math.abs(worldZ - DEBUG_CAVE_Z) > DEBUG_CAVE_RADIUS || Math.floorMod(y, 16) != 0) {
            return;
        }
        UkGeoMod.LOGGER.info(
            "UKGeo cave debug x={} y={} z={} terrainTop={} surfaceDepth={} vanillaTop={} delegateRange={}..{} mode={} canDelegate={} delegateCave={}",
            worldX,
            y,
            worldZ,
            terrainTop,
            terrainTop - y,
            vanillaTop,
            caveMask.delegateMinY(),
            caveMask.delegateMaxY(),
            caveMask.mode(y),
            caveMask.canSampleDelegate(y),
            delegateCave
        );
    }

    static BlockState columnStateFor(
        int y,
        int surfaceY,
        int minBuildY,
        BlockState surfaceRock,
        BlockState exposedSurfaceRock,
        boolean steep,
        UkGeoChunkGenerator.RiverShape river,
        int originalSurfaceY,
        boolean coastalBeach,
        int vegetationClass,
        int seaLevelY
    ) {
        if (river.hasWater() && y > surfaceY && y <= river.waterSurfaceY()) {
            return Blocks.WATER.defaultBlockState();
        }
        if (river.hasWater() && y > river.waterSurfaceY() && y <= originalSurfaceY) {
            return y <= seaLevelY ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }
        if (y > surfaceY) {
            return y <= seaLevelY ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }
        if (river.hasWater() && y >= surfaceY - 1 && y < river.waterSurfaceY()) {
            return river.floorMaterial();
        }
        if (!river.hasWater() && (originalSurfaceY < seaLevelY || coastalBeach) && surfaceY <= seaLevelY + 2 && y >= surfaceY - 2) {
            return Blocks.SAND.defaultBlockState();
        }
        if (steep && y == surfaceY) {
            return exposedSurfaceRock;
        }
        if (steep && y >= surfaceY - 4) {
            return surfaceRock;
        }
        if (y == surfaceY) {
            // Urban/suburban (vegetation class 11) should be grass on the surface, not stone.
            return Blocks.GRASS_BLOCK.defaultBlockState();
        }
        if (y >= surfaceY - 3) {
            return Blocks.DIRT.defaultBlockState();
        }
        int rockDepth = isRestrictedSurfaceOre(surfaceRock) ? MINERAL_DEPOSIT_SURFACE_DEPTH : 12;
        if (y >= surfaceY - rockDepth) {
            return surfaceRock;
        }
        return y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }

    static BlockState exposedSurfaceRock(int worldX, int worldZ, BlockState surfaceRock) {
        if (!isRestrictedSurfaceOre(surfaceRock) || allowRareExposedSurfaceOre(worldX, worldZ, surfaceRock)) {
            return surfaceRock;
        }
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    private static boolean isRestrictedSurfaceOre(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return switch (id) {
            case "minecraft:andesite",
                 "minecraft:diorite",
                 "minecraft:granite",
                 "create:ochrum",
                 "minecraft:calcite",
                 "create:scoria",
                 "minecraft:tuff",
                 "create:crimsite",
                 "create:limestone",
                 "create:asurine",
                 "create:veridium",
                 "minecraft:smooth_basalt" -> true;
            default -> false;
        };
    }

    private static boolean allowRareExposedSurfaceOre(int worldX, int worldZ, BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        long hash = 0x9e3779b97f4a7c15L;
        hash ^= (long) worldX * 0xbf58476d1ce4e5b9L;
        hash ^= (long) worldZ * 0x94d049bb133111ebL;
        hash ^= (long) id.hashCode() * 0x632be59bd9b4e019L;
        hash ^= hash >>> 30;
        hash *= 0xbf58476d1ce4e5b9L;
        hash ^= hash >>> 27;
        hash *= 0x94d049bb133111ebL;
        hash ^= hash >>> 31;
        return Math.floorMod(hash, 100) < 5;
    }

    private static void setBlock(
        ChunkAccess chunk,
        BlockPos.MutableBlockPos cursor,
        int localX,
        int y,
        int localZ,
        BlockState state
    ) {
        if (FAST_SECTION_WRITES && !chunk.isOutsideBuildHeight(y)) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
            section.setBlockState(localX, y & 15, localZ, state, false);
            return;
        }
        chunk.setBlockState(cursor.set(localX, y, localZ), state, false);
    }

    private static void logTiming(String label, ChunkAccess chunk, long startNanos) {
        if (DEBUG_GEN_TIMINGS && DEBUG_FULL_TIMING_SPAM) {
            UkGeoMod.LOGGER.info("UKGeo timing chunk {} {}={}ms", chunk.getPos(), label, nanosToMillis(System.nanoTime() - startNanos));
        }
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    record ColumnPlan(
        int originalSurfaceY,
        int terrainTop,
        int columnTop,
        boolean steep,
        boolean coastalBeach,
        UkGeoChunkGenerator.RiverShape river,
        int vegetationClass,
        BlockState surfaceRock,
        BlockState exposedSurfaceRock,
        boolean hasHeightData
    ) {
    }

    record OrePlacement(int localX, int y, int localZ, BlockState state) {
    }

    record Plan(ColumnPlan[] columns, OrePlacement[] orePlacements, int seaLevelY) {
    }

    private record WaterProtection(boolean hasWater, int waterSurfaceY, int floorY) {
        static WaterProtection none() {
            return new WaterProtection(false, Integer.MIN_VALUE, Integer.MIN_VALUE);
        }
    }

    enum CaveState {
        SOLID,
        AIR,
        LAVA
    }

    enum CaveMode {
        DELEGATE,
        SOLID
    }

    record CaveMask(
        boolean usesDelegate,
        int delegateMinY,
        int delegateMaxY
    ) {
        static CaveMask none() {
            return new CaveMask(false, 0, -1);
        }

        boolean canSampleDelegate(int y) {
            return usesDelegate && y >= delegateMinY && y <= delegateMaxY;
        }

        CaveMode mode(int y) {
            return canSampleDelegate(y) ? CaveMode.DELEGATE : CaveMode.SOLID;
        }

        boolean mayCarveAtY(int y, int vanillaTop) {
            return usesDelegate && y <= vanillaTop && canSampleDelegate(y);
        }
    }

}
