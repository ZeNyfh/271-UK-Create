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
import net.minecraft.world.level.material.Fluids;

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
        // Baked station water Y is authoritative. Do not call fitWaterToBanks*,
        // levelGentleReachWater, or smoothRiverWaterSurfacesLocally.
        cutCascadeLips(columns, minBuildY, maxBuildY);
        smoothFreshwaterLakeBeds(columns, minBuildY, maxBuildY);
        smoothRiverBedsInComponents(columns, minBuildY, maxBuildY);
        smoothDryRiverBanks(columns, minBuildY, maxBuildY);
        long oreStartNanos = System.nanoTime();
        Plan plan = new Plan(columns, generator.buildOrePlacements(data, chunk, columns), generator.seaLevel());
        long oreElapsedNanos = System.nanoTime() - oreStartNanos;
        logTiming("ChunkTerrainPlanner.orePlanning", chunk, oreStartNanos);
        long totalElapsedNanos = System.nanoTime() - startNanos;
        logTiming("ChunkTerrainPlanner.compute", chunk, startNanos);
        generator.recordPlanStageTimings(
            windowElapsedNanos,
            surfaceElapsedNanos,
            waterElapsedNanos,
            oreElapsedNanos,
            totalElapsedNanos
        );
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
        LevelChunkSection[] sectionCache = FAST_SECTION_WRITES ? new LevelChunkSection[chunk.getSectionsCount()] : null;
        SectionWriteCursor sectionCursor = FAST_SECTION_WRITES ? new SectionWriteCursor() : null;
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
                    sectionCache,
                    sectionCursor,
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
        applyOres(plan, chunk, sectionCache, sectionCursor);
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
                int solidContact = Math.max(minBuildY, column.terrainTop());
                boolean foundSolid = false;
                for (int y = column.terrainTop(); y >= minBuildY + 1; y--) {
                    BlockState existing = chunk.getBlockState(cursor.set(localX, y, localZ));
                    if (existing.isAir() || existing.is(Blocks.WATER) || !existing.getFluidState().isEmpty()) {
                        continue;
                    }
                    solidContact = y;
                    foundSolid = true;
                    break;
                }
                if (!foundSolid) {
                    solidContact = minBuildY;
                }
                BlockState floor = column.river().hasWater()
                    ? column.river().floorMaterial()
                    : Blocks.GRAVEL.defaultBlockState();
                if (floor == null) {
                    floor = Blocks.GRAVEL.defaultBlockState();
                }
                if (solidContact >= minBuildY) {
                    setBlock(chunk, cursor, localX, solidContact, localZ, floor);
                }
                for (int y = solidContact + 1; y <= waterSurfaceY; y++) {
                    setBlock(chunk, cursor, localX, y, localZ, Blocks.WATER.defaultBlockState());
                }
                clearWaterAboveSurface(chunk, cursor, localX, localZ, waterSurfaceY);
            }
        }
        logTiming("ChunkTerrainPlanner.enforceWaterColumns", chunk, startNanos);
    }

    /**
     * After noise caves, remove aquifer/cave water outside the planned surface column.
     * QA probes count highest water in a vertical scan — sealed cave pools and pockets above
     * the reach surface inflate top_span even when the channel plane is flat.
     */
    static void stripStrayCaveWaterAfterCarving(Plan plan, ChunkAccess chunk) {
        long startNanos = System.nanoTime();
        int minBuildY = chunk.getMinBuildHeight();
        int maxBuildY = chunk.getMaxBuildHeight() - 1;
        int seaLevelY = plan.seaLevelY();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int localZ = 0; localZ < CHUNK_SIZE; localZ++) {
            for (int localX = 0; localX < CHUNK_SIZE; localX++) {
                ColumnPlan column = plan.columns[localZ * CHUNK_SIZE + localX];
                int landTop = Math.max(column.terrainTop(), column.originalSurfaceY());
                int plannedSurface = plannedWaterSurfaceY(column, seaLevelY);
                int scanTop = maxBuildY;
                if (plannedSurface != Integer.MIN_VALUE) {
                    int bed = column.river().terrainSurfaceY();
                    for (int y = minBuildY; y <= scanTop; y++) {
                        if (!chunk.getBlockState(cursor.set(localX, y, localZ)).is(Blocks.WATER)) {
                            continue;
                        }
                        if (y < bed || y > plannedSurface) {
                            setBlock(chunk, cursor, localX, y, localZ, Blocks.AIR.defaultBlockState());
                        }
                    }
                } else if (column.originalSurfaceY() >= seaLevelY) {
                    for (int y = minBuildY; y <= scanTop; y++) {
                        if (chunk.getBlockState(cursor.set(localX, y, localZ)).is(Blocks.WATER)) {
                            setBlock(chunk, cursor, localX, y, localZ, Blocks.AIR.defaultBlockState());
                        }
                    }
                }
            }
        }
        logTiming("ChunkTerrainPlanner.stripStrayCaveWater", chunk, startNanos);
    }


    /**
     * Light local smooth of river water surfaces: 3×3 median of neighbour water Y,
     * moving each column by at most 1 toward the median. Never forces a shared plane.
     */
    private static void smoothRiverWaterSurfacesLocally(ColumnPlan[] columns, int minBuildY, int maxBuildY) {
        int[] waterY = new int[columns.length];
        boolean[] hasWater = new boolean[columns.length];
        for (int index = 0; index < columns.length; index++) {
            hasWater[index] = columns[index].river().hasWater();
            waterY[index] = hasWater[index] ? columns[index].river().waterSurfaceY() : Integer.MIN_VALUE;
        }
        int[] scratch = new int[9];
        for (int index = 0; index < columns.length; index++) {
            if (!hasWater[index]) {
                continue;
            }
            int localZ = index / CHUNK_SIZE;
            int localX = index - localZ * CHUNK_SIZE;
            int count = 0;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = localX + dx;
                    int nz = localZ + dz;
                    if (nx < 0 || nz < 0 || nx >= CHUNK_SIZE || nz >= CHUNK_SIZE) {
                        continue;
                    }
                    int neighbor = nz * CHUNK_SIZE + nx;
                    if (!hasWater[neighbor]) {
                        continue;
                    }
                    scratch[count++] = waterY[neighbor];
                }
            }
            if (count == 0) {
                continue;
            }
            java.util.Arrays.sort(scratch, 0, count);
            int median = scratch[count / 2];
            int current = waterY[index];
            int target = current;
            if (median > current) {
                target = Math.min(current + 1, median);
            } else if (median < current) {
                target = Math.max(current - 1, median);
            }
            target = Math.clamp(target, minBuildY + 1, maxBuildY);
            if (target == current) {
                continue;
            }
            ColumnPlan column = columns[index];
            UkGeoChunkGenerator.RiverShape river = column.river();
            int depth = Math.max(1, river.waterSurfaceY() - river.terrainSurfaceY());
            int newBed = RiverBankFit.bedForWater(target, depth, minBuildY);
            columns[index] = withRiverBedAndWater(column, newBed, target, minBuildY, maxBuildY);
        }
    }


    /**
     * Fit each river water column to adjacent dry banks independently.
     * Raise banks by at most {@link RiverBankFit#MAX_BANK_RAISE}, otherwise lower that column's water.
     * Does not force a shared plane across a band or component.
     */
    private static void fitWaterToBanks(ColumnPlan[] columns, int minBuildY, int maxBuildY) {
        fitWaterToBanks(columns, null, 0, minBuildY, maxBuildY);
    }

    /**
     * Per-column bank fit using dry {@link ColumnPlan#originalSurfaceY()} only (carved shelves
     * must not pull water down). Near chunk borders, also samples outward DEM bank tops.
     * Raise banks by at most {@link RiverBankFit#MAX_BANK_RAISE}, otherwise lower that column's water.
     */
    private static void fitWaterToBanks(
        ColumnPlan[] columns,
        int[] surfaceGrid,
        int gridSize,
        int minBuildY,
        int maxBuildY
    ) {
        final int bankRadius = 3;
        int[] bankRaises = new int[columns.length];
        java.util.Arrays.fill(bankRaises, Integer.MIN_VALUE);
        int[] bankScratch = new int[192];

        for (int index = 0; index < columns.length; index++) {
            ColumnPlan column = columns[index];
            if (!column.river().hasWater()) {
                continue;
            }
            int localZ = index / CHUNK_SIZE;
            int localX = index - localZ * CHUNK_SIZE;
            int waterSurface = column.river().waterSurfaceY();
            int bankCount = 0;
            for (int dz = -bankRadius; dz <= bankRadius; dz++) {
                for (int dx = -bankRadius; dx <= bankRadius; dx++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int nx = localX + dx;
                    int nz = localZ + dz;
                    if (nx < 0 || nz < 0 || nx >= CHUNK_SIZE || nz >= CHUNK_SIZE) {
                        continue;
                    }
                    ColumnPlan neighbor = columns[nz * CHUNK_SIZE + nx];
                    if (neighbor.river().hasWater()) {
                        continue;
                    }
                    if (bankCount < bankScratch.length) {
                        bankScratch[bankCount++] = neighbor.originalSurfaceY();
                    }
                }
            }
            if (surfaceGrid != null && gridSize > 0 && isNearChunkBorder(localX, localZ, 2)) {
                bankCount = appendOutwardBorderBankTops(
                    bankScratch, bankCount, surfaceGrid, gridSize, localX, localZ, waterSurface
                );
            }
            int minBank = robustLowBank(bankScratch, bankCount);
            int fitted = RiverBankFit.resolveWaterSurface(waterSurface, minBank);
            fitted = Math.clamp(fitted, minBuildY + 1, maxBuildY);
            if (fitted != waterSurface) {
                UkGeoChunkGenerator.RiverShape river = column.river();
                // Preserve bowl depth while translating the water plane.
                int depth = Math.max(1, river.waterSurfaceY() - river.terrainSurfaceY());
                int newBed = RiverBankFit.bedForWater(fitted, depth, minBuildY);
                columns[index] = withRiverBedAndWater(column, newBed, fitted, minBuildY, maxBuildY);
                waterSurface = fitted;
            }
            recordBankRaises(columns, bankRaises, localX, localZ, bankRadius, waterSurface, minBank);
        }

        applyBankRaises(columns, bankRaises, minBuildY, maxBuildY);
    }

    /** Robust low bank: true min for tiny samples; else ~10th percentile after sort. */
    private static int robustLowBank(int[] bankHeights, int count) {
        if (count <= 0) {
            return Integer.MAX_VALUE;
        }
        if (count < 4) {
            int min = bankHeights[0];
            for (int i = 1; i < count; i++) {
                min = Math.min(min, bankHeights[i]);
            }
            return min;
        }
        java.util.Arrays.sort(bankHeights, 0, count);
        return bankHeights[Math.max(1, count / 10)];
    }

    /**
     * Flatten gentle (non-cascade) water components to one median water plane.
     * Steep components with span>2 keep stepped surfaces for cascades.
     * Border-touching gentle reaches prefer dry-bank + DEM border consensus so
     * neighbouring chunks agree without cross-chunk plane caches.
     */
    static void levelGentleReachWater(ColumnPlan[] columns, int[] componentOf, int minBuildY, int maxBuildY) {
        levelGentleReachWater(columns, componentOf, null, 0, minBuildY, maxBuildY);
    }

    static void levelGentleReachWater(
        ColumnPlan[] columns,
        int[] componentOf,
        int[] surfaceGrid,
        int gridSize,
        int minBuildY,
        int maxBuildY
    ) {
        int maxId = maxLabel(componentOf);
        if (maxId < 0) {
            return;
        }
        int componentCount = maxId + 1;
        int[] counts = new int[componentCount];
        int[] steepCounts = new int[componentCount];
        int[] minWater = new int[componentCount];
        int[] maxWater = new int[componentCount];
        java.util.Arrays.fill(minWater, Integer.MAX_VALUE);
        java.util.Arrays.fill(maxWater, Integer.MIN_VALUE);

        for (int index = 0; index < columns.length; index++) {
            int id = componentOf[index];
            if (id < 0 || !columns[index].river().hasWater()) {
                continue;
            }
            int waterY = columns[index].river().waterSurfaceY();
            counts[id]++;
            if (columns[index].steep()) {
                steepCounts[id]++;
            }
            minWater[id] = Math.min(minWater[id], waterY);
            maxWater[id] = Math.max(maxWater[id], waterY);
        }

        int[] medianWater = new int[componentCount];
        java.util.Arrays.fill(medianWater, Integer.MIN_VALUE);
        for (int id = 0; id < componentCount; id++) {
            int count = counts[id];
            if (count < 2) {
                continue;
            }
            int span = maxWater[id] - minWater[id];
            boolean mostlySteep = steepCounts[id] * 2 > count;
            // Wide rivers can trip mostlySteep from DEM noise; only skip small cascades.
            if (mostlySteep && span > 2 && count < 32) {
                continue;
            }
            boolean touchesChunkBorder = false;
            for (int index = 0; index < columns.length; index++) {
                if (componentOf[index] != id || !columns[index].river().hasWater()) {
                    continue;
                }
                int localZ = index / CHUNK_SIZE;
                int localX = index - localZ * CHUNK_SIZE;
                if (isNearChunkBorder(localX, localZ, 2)) {
                    touchesChunkBorder = true;
                    break;
                }
            }
            if (span <= 0 && !touchesChunkBorder) {
                continue;
            }

            int[] values = new int[Math.max(count + 128, 160)];
            int filled = 0;
            for (int index = 0; index < columns.length; index++) {
                if (componentOf[index] != id || !columns[index].river().hasWater()) {
                    continue;
                }
                values[filled++] = columns[index].river().waterSurfaceY();
            }
            int waterFilled = filled;
            int anchor = minWater[id];
            int plane;
            if (surfaceGrid != null && gridSize > 0 && touchesChunkBorder && count >= 24) {
                // Wide border reaches (Thames): plane from shared seam IQR only (no in-chunk dry banks),
                // so mid-channel and shore chunks that share an edge agree.
                int seamFilled = appendFullBorderStripBanks(
                    values, waterFilled, surfaceGrid, gridSize, columns, componentOf, id, anchor
                );
                int seamCount = seamFilled - waterFilled;
                if (seamCount >= 2) {
                    java.util.Arrays.sort(values, waterFilled, seamFilled);
                    plane = values[waterFilled + seamCount / 2];
                } else if (span <= 0) {
                    continue;
                } else {
                    java.util.Arrays.sort(values, 0, waterFilled);
                    plane = values[waterFilled / 2];
                }
            } else {
                for (int index = 0; index < columns.length; index++) {
                    if (componentOf[index] != id || !columns[index].river().hasWater()) {
                        continue;
                    }
                    int localZ = index / CHUNK_SIZE;
                    int localX = index - localZ * CHUNK_SIZE;
                    filled = appendInChunkDryBankTops(values, filled, columns, localX, localZ, 3);
                }
                int bankCount = filled - waterFilled;
                if (bankCount >= 2) {
                    java.util.Arrays.sort(values, waterFilled, filled);
                    int minBank = values[waterFilled];
                    int robustMin = bankCount >= 8
                        ? values[waterFilled + Math.max(1, bankCount / 10)]
                        : minBank;
                    int bankMedian = values[waterFilled + bankCount / 2];
                    plane = RiverBankFit.resolveWaterSurface(bankMedian, robustMin);
                } else if (span <= 0) {
                    continue;
                } else {
                    java.util.Arrays.sort(values, 0, waterFilled);
                    plane = span > 4
                        ? values[Math.max(0, waterFilled / 4)]
                        : values[waterFilled / 2];
                }
            }
            medianWater[id] = plane;
        }

        for (int index = 0; index < columns.length; index++) {
            int id = componentOf[index];
            if (id < 0 || medianWater[id] == Integer.MIN_VALUE) {
                continue;
            }
            ColumnPlan column = columns[index];
            if (!column.river().hasWater()) {
                continue;
            }
            int target = Math.clamp(medianWater[id], minBuildY + 1, maxBuildY);
            UkGeoChunkGenerator.RiverShape river = column.river();
            if (target == river.waterSurfaceY()) {
                continue;
            }
            int depth = Math.max(1, river.waterSurfaceY() - river.terrainSurfaceY());
            int newBed = RiverBankFit.bedForWater(target, depth, minBuildY);
            columns[index] = withRiverBedAndWater(column, newBed, target, minBuildY, maxBuildY);
        }
    }


    /**
     * Pull tiny gentle side pools (component count < 8) onto the plane of an adjacent main reach.
     */
    private static void snapSatelliteGentleWater(ColumnPlan[] columns, int[] componentOf, int minBuildY, int maxBuildY) {
        boolean[] steepCascade = steepCascadeWaterComponents(columns, componentOf);
        int maxId = maxLabel(componentOf);
        if (maxId < 0) {
            return;
        }
        int componentCount = maxId + 1;
        int[] counts = new int[componentCount];
        int[] minWater = new int[componentCount];
        int[] maxWater = new int[componentCount];
        java.util.Arrays.fill(minWater, Integer.MAX_VALUE);
        java.util.Arrays.fill(maxWater, Integer.MIN_VALUE);
        for (int index = 0; index < columns.length; index++) {
            int id = componentOf[index];
            if (id < 0 || !columns[index].river().hasWater()) {
                continue;
            }
            int waterY = columns[index].river().waterSurfaceY();
            counts[id]++;
            minWater[id] = Math.min(minWater[id], waterY);
            maxWater[id] = Math.max(maxWater[id], waterY);
        }
        int[] componentPlane = new int[componentCount];
        java.util.Arrays.fill(componentPlane, Integer.MIN_VALUE);
        for (int id = 0; id < componentCount; id++) {
            if (counts[id] < 8 || steepCascade[id]) {
                continue;
            }
            int span = maxWater[id] - minWater[id];
            if (span > 2) {
                continue;
            }
            int[] values = new int[counts[id]];
            int filled = 0;
            for (int index = 0; index < columns.length; index++) {
                if (componentOf[index] == id && columns[index].river().hasWater()) {
                    values[filled++] = columns[index].river().waterSurfaceY();
                }
            }
            java.util.Arrays.sort(values, 0, filled);
            componentPlane[id] = values[filled / 2];
        }

        int[][] neighborOffsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int index = 0; index < columns.length; index++) {
            ColumnPlan column = columns[index];
            if (!column.river().hasWater()) {
                continue;
            }
            int id = componentOf[index];
            if (id < 0 || steepCascade[id] || counts[id] >= 8) {
                continue;
            }
            int waterY = column.river().waterSurfaceY();
            int localZ = index / CHUNK_SIZE;
            int localX = index - localZ * CHUNK_SIZE;
            int hostPlane = Integer.MIN_VALUE;
            for (int[] offset : neighborOffsets) {
                int nx = localX + offset[0];
                int nz = localZ + offset[1];
                if (nx < 0 || nz < 0 || nx >= CHUNK_SIZE || nz >= CHUNK_SIZE) {
                    continue;
                }
                int nIndex = nz * CHUNK_SIZE + nx;
                if (!columns[nIndex].river().hasWater()) {
                    continue;
                }
                int neighborId = componentOf[nIndex];
                if (neighborId < 0 || neighborId == id) {
                    continue;
                }
                int plane = componentPlane[neighborId];
                if (plane == Integer.MIN_VALUE) {
                    continue;
                }
                hostPlane = hostPlane == Integer.MIN_VALUE ? plane : Math.min(hostPlane, plane);
            }
            if (hostPlane == Integer.MIN_VALUE || Math.abs(waterY - hostPlane) <= 1) {
                continue;
            }
            UkGeoChunkGenerator.RiverShape river = column.river();
            int depth = Math.max(1, river.waterSurfaceY() - river.terrainSurfaceY());
            int newBed = RiverBankFit.bedForWater(hostPlane, depth, minBuildY);
            columns[index] = withRiverBedAndWater(column, newBed, hostPlane, minBuildY, maxBuildY);
        }
    }

    private static boolean isNearChunkBorder(int localX, int localZ) {
        return isNearChunkBorder(localX, localZ, 1);
    }

    private static boolean isNearChunkBorder(int localX, int localZ, int margin) {
        int m = Math.max(0, margin);
        return localX <= m || localX >= CHUNK_SIZE - 1 - m || localZ <= m || localZ >= CHUNK_SIZE - 1 - m;
    }

    private static int borderDistance(int localX, int localZ) {
        return Math.min(
            Math.min(localX, CHUNK_SIZE - 1 - localX),
            Math.min(localZ, CHUNK_SIZE - 1 - localZ)
        );
    }

    /**
     * Pull gentle river water near chunk borders onto one shared bank-consensus plane per
     * component (DEM border + dry banks), including a soft interior band.
     */
    private static void alignGentleWaterChunkEdges(
        ColumnPlan[] columns,
        int[] componentOf,
        int[] surfaceGrid,
        int gridSize,
        int minBuildY,
        int maxBuildY
    ) {
        boolean[] steepCascade = steepCascadeWaterComponents(columns, componentOf);
        int maxId = maxLabel(componentOf);
        if (maxId < 0) {
            return;
        }
        int componentCount = maxId + 1;
        final int maxHints = 192;
        int[][] componentHints = new int[componentCount][];
        int[] hintCounts = new int[componentCount];
        boolean[] touchesBorder = new boolean[componentCount];

        for (int index = 0; index < columns.length; index++) {
            ColumnPlan column = columns[index];
            if (!column.river().hasWater()) {
                continue;
            }
            int componentId = componentOf[index];
            if (componentId < 0 || componentId >= componentCount) {
                continue;
            }
            if (steepCascade[componentId]) {
                continue;
            }
            if (column.steep() && componentWaterSpan(columns, componentOf, componentId) > 2
                && componentWaterCount(columns, componentOf, componentId) < 32) {
                continue;
            }
            int localZ = index / CHUNK_SIZE;
            int localX = index - localZ * CHUNK_SIZE;
            if (!isNearChunkBorder(localX, localZ, 3)) {
                continue;
            }
            touchesBorder[componentId] = true;
            if (componentHints[componentId] == null) {
                componentHints[componentId] = new int[maxHints];
            }
            int[] bankHints = componentHints[componentId];
            int hintCount = hintCounts[componentId];
            int waterY = column.river().waterSurfaceY();
            hintCount = appendInChunkDryBankTops(bankHints, hintCount, columns, localX, localZ, 3);
            if (surfaceGrid != null && gridSize > 0) {
                hintCount = appendOutwardBorderBankTops(bankHints, hintCount, surfaceGrid, gridSize, localX, localZ, waterY);
            }
            hintCounts[componentId] = hintCount;
        }

        int[] componentTarget = new int[componentCount];
        java.util.Arrays.fill(componentTarget, Integer.MIN_VALUE);
        for (int id = 0; id < componentCount; id++) {
            if (!touchesBorder[id] || hintCounts[id] < 2) {
                continue;
            }
            int[] bankHints = componentHints[id];
            int hintCount = hintCounts[id];
            java.util.Arrays.sort(bankHints, 0, hintCount);
            int minBank = bankHints[0];
            int robustMin = hintCount >= 8
                ? bankHints[Math.max(1, hintCount / 10)]
                : minBank;
            int medianBank = bankHints[hintCount / 2];
            componentTarget[id] = Math.clamp(
                RiverBankFit.resolveWaterSurface(medianBank, robustMin),
                minBuildY + 1,
                maxBuildY
            );
        }

        for (int index = 0; index < columns.length; index++) {
            ColumnPlan column = columns[index];
            if (!column.river().hasWater()) {
                continue;
            }
            int componentId = componentOf[index];
            if (componentId < 0 || componentId >= componentCount || componentTarget[componentId] == Integer.MIN_VALUE) {
                continue;
            }
            if (borderDistance(localX(index), localZ(index)) > 3) {
                continue;
            }
            int waterY = column.river().waterSurfaceY();
            int target = Math.clamp(componentTarget[componentId], minBuildY + 1, maxBuildY);
            if (target == waterY) {
                continue;
            }
            UkGeoChunkGenerator.RiverShape river = column.river();
            int depth = Math.max(1, river.waterSurfaceY() - river.terrainSurfaceY());
            int newBed = RiverBankFit.bedForWater(target, depth, minBuildY);
            columns[index] = withRiverBedAndWater(column, newBed, target, minBuildY, maxBuildY);
        }
    }

    private static int localX(int index) {
        return index - (index / CHUNK_SIZE) * CHUNK_SIZE;
    }

    private static int localZ(int index) {
        return index / CHUNK_SIZE;
    }

    private static int componentWaterCount(ColumnPlan[] columns, int[] componentOf, int componentId) {
        int count = 0;
        for (int index = 0; index < columns.length; index++) {
            if (componentOf[index] == componentId && columns[index].river().hasWater()) {
                count++;
            }
        }
        return count;
    }

    private static int appendInChunkDryBankTops(
        int[] bankHints,
        int hintCount,
        ColumnPlan[] columns,
        int localX,
        int localZ,
        int radius
    ) {
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = localX + dx;
                int nz = localZ + dz;
                if (nx < 0 || nz < 0 || nx >= CHUNK_SIZE || nz >= CHUNK_SIZE) {
                    continue;
                }
                ColumnPlan neighbor = columns[nz * CHUNK_SIZE + nx];
                if (neighbor.river().hasWater()) {
                    continue;
                }
                if (hintCount < bankHints.length) {
                    bankHints[hintCount++] = Math.min(neighbor.terrainTop(), neighbor.originalSurfaceY());
                }
            }
        }
        return hintCount;
    }

    private static boolean[] steepCascadeWaterComponents(ColumnPlan[] columns, int[] componentOf) {
        int maxId = maxLabel(componentOf);
        if (maxId < 0) {
            return new boolean[0];
        }
        int componentCount = maxId + 1;
        int[] counts = new int[componentCount];
        int[] steepCounts = new int[componentCount];
        int[] minWater = new int[componentCount];
        int[] maxWater = new int[componentCount];
        java.util.Arrays.fill(minWater, Integer.MAX_VALUE);
        java.util.Arrays.fill(maxWater, Integer.MIN_VALUE);

        for (int index = 0; index < columns.length; index++) {
            int id = componentOf[index];
            if (id < 0 || !columns[index].river().hasWater()) {
                continue;
            }
            int waterY = columns[index].river().waterSurfaceY();
            counts[id]++;
            if (columns[index].steep()) {
                steepCounts[id]++;
            }
            minWater[id] = Math.min(minWater[id], waterY);
            maxWater[id] = Math.max(maxWater[id], waterY);
        }

        boolean[] steepCascade = new boolean[componentCount];
        for (int id = 0; id < componentCount; id++) {
            if (counts[id] < 2) {
                continue;
            }
            int span = maxWater[id] - minWater[id];
            boolean mostlySteep = steepCounts[id] * 2 > counts[id];
            steepCascade[id] = mostlySteep && span > 2 && counts[id] < 32;
        }
        return steepCascade;
    }

    private static int componentWaterSpan(ColumnPlan[] columns, int[] componentOf, int componentId) {
        int minWater = Integer.MAX_VALUE;
        int maxWater = Integer.MIN_VALUE;
        for (int index = 0; index < columns.length; index++) {
            if (componentOf[index] != componentId || !columns[index].river().hasWater()) {
                continue;
            }
            int waterY = columns[index].river().waterSurfaceY();
            minWater = Math.min(minWater, waterY);
            maxWater = Math.max(maxWater, waterY);
        }
        if (minWater == Integer.MAX_VALUE) {
            return 0;
        }
        return maxWater - minWater;
    }

    private static int appendOutwardBorderBankTops(
        int[] bankHints,
        int hintCount,
        int[] surfaceGrid,
        int gridSize,
        int localX,
        int localZ,
        int waterY
    ) {
        int floorY = waterY - RiverBankFit.MAX_BANK_RAISE;
        int gzMin = Math.max(0, localZ - 3);
        int gzMax = Math.min(CHUNK_SIZE - 1, localZ + 3);

        if (localX <= 1) {
            for (int lz = gzMin; lz <= gzMax; lz++) {
                int row = (BORDER + lz) * gridSize;
                for (int gx = 0; gx < BORDER; gx++) {
                    int surface = surfaceGrid[row + gx];
                    if (surface >= floorY && hintCount < bankHints.length) {
                        bankHints[hintCount++] = surface;
                    }
                }
            }
        }
        if (localX >= CHUNK_SIZE - 2) {
            int gxMin = BORDER + CHUNK_SIZE;
            for (int lz = gzMin; lz <= gzMax; lz++) {
                int row = (BORDER + lz) * gridSize;
                for (int gx = gxMin; gx < gridSize; gx++) {
                    int surface = surfaceGrid[row + gx];
                    if (surface >= floorY && hintCount < bankHints.length) {
                        bankHints[hintCount++] = surface;
                    }
                }
            }
        }
        int gxLo = BORDER + localX - 3;
        int gxHi = BORDER + localX + 3;
        if (localZ <= 1) {
            for (int gz = 0; gz < BORDER; gz++) {
                for (int gx = Math.max(0, gxLo); gx <= Math.min(gridSize - 1, gxHi); gx++) {
                    int surface = surfaceGrid[gz * gridSize + gx];
                    if (surface >= floorY && hintCount < bankHints.length) {
                        bankHints[hintCount++] = surface;
                    }
                }
            }
        }
        if (localZ >= CHUNK_SIZE - 2) {
            int gzMinOut = BORDER + CHUNK_SIZE;
            for (int gz = gzMinOut; gz < gridSize; gz++) {
                for (int gx = Math.max(0, gxLo); gx <= Math.min(gridSize - 1, gxHi); gx++) {
                    int surface = surfaceGrid[gz * gridSize + gx];
                    if (surface >= floorY && hintCount < bankHints.length) {
                        bankHints[hintCount++] = surface;
                    }
                }
            }
        }
        return hintCount;
    }

    /**
     * Sample the seam-symmetric DEM strip (outward+inward BORDER) on every edge this
     * component touches. Uses IQR of the strip so neighbouring chunks that share the
     * strip agree without depending on provisional waterY floors.
     */
    private static int appendFullBorderStripBanks(
        int[] bankHints,
        int hintCount,
        int[] surfaceGrid,
        int gridSize,
        ColumnPlan[] columns,
        int[] componentOf,
        int componentId,
        int anchorY
    ) {
        boolean touchW = false;
        boolean touchE = false;
        boolean touchN = false;
        boolean touchS = false;
        for (int index = 0; index < columns.length; index++) {
            if (componentOf[index] != componentId || !columns[index].river().hasWater()) {
                continue;
            }
            int localZ = index / CHUNK_SIZE;
            int localX = index - localZ * CHUNK_SIZE;
            if (localX <= 1) {
                touchW = true;
            }
            if (localX >= CHUNK_SIZE - 2) {
                touchE = true;
            }
            if (localZ <= 1) {
                touchN = true;
            }
            if (localZ >= CHUNK_SIZE - 2) {
                touchS = true;
            }
        }
        int[] seam = new int[512];
        int seamCount = 0;
        if (touchW) {
            for (int lz = 0; lz < CHUNK_SIZE; lz++) {
                int row = (BORDER + lz) * gridSize;
                for (int gx = 0; gx < BORDER * 2; gx++) {
                    if (seamCount < seam.length) {
                        seam[seamCount++] = surfaceGrid[row + gx];
                    }
                }
            }
        }
        if (touchE) {
            int gxMin = gridSize - BORDER * 2;
            for (int lz = 0; lz < CHUNK_SIZE; lz++) {
                int row = (BORDER + lz) * gridSize;
                for (int gx = gxMin; gx < gridSize; gx++) {
                    if (seamCount < seam.length) {
                        seam[seamCount++] = surfaceGrid[row + gx];
                    }
                }
            }
        }
        if (touchN) {
            for (int gz = 0; gz < BORDER * 2; gz++) {
                for (int gx = BORDER; gx < BORDER + CHUNK_SIZE; gx++) {
                    if (seamCount < seam.length) {
                        seam[seamCount++] = surfaceGrid[gz * gridSize + gx];
                    }
                }
            }
        }
        if (touchS) {
            int gzMin = gridSize - BORDER * 2;
            for (int gz = gzMin; gz < gridSize; gz++) {
                for (int gx = BORDER; gx < BORDER + CHUNK_SIZE; gx++) {
                    if (seamCount < seam.length) {
                        seam[seamCount++] = surfaceGrid[gz * gridSize + gx];
                    }
                }
            }
        }
        if (seamCount < 4) {
            return hintCount;
        }
        java.util.Arrays.sort(seam, 0, seamCount);
        // Raw IQR — no waterY/anchor filter so both chunks sharing the strip agree.
        int lo = seamCount / 4;
        int hi = Math.max(lo + 1, (seamCount * 3) / 4);
        for (int i = lo; i < hi && hintCount < bankHints.length; i++) {
            bankHints[hintCount++] = seam[i];
        }
        return hintCount;
    }

    private static int medianOf(int[] values, int count) {
        java.util.Arrays.sort(values, 0, count);
        return values[count / 2];
    }

    /**
     * Fit water to banks: per-column on steep/cascade components, one shared fit on gentle reaches.
     * Raise banks by at most {@link RiverBankFit#MAX_BANK_RAISE}, otherwise lower water.
     *
     * @return true when bank-fit raised dry columns (topology may differ for leveling).
     */
    private static boolean fitWaterToBanksByComponent(ColumnPlan[] columns, int[] componentOf, int minBuildY, int maxBuildY) {
        return fitWaterToBanksByComponent(columns, componentOf, null, 0, minBuildY, maxBuildY);
    }

    /**
     * Fit water to banks by connected component. Gentle reaches share one lowest-bank plane so
     * mid-channel columns (no dry neighbour in radius) inherit edge bank fits. Steep/cascade
     * components stay per-column. Bank samples use {@link ColumnPlan#originalSurfaceY()} only;
     * near chunk borders also samples outward DEM via {@link #appendOutwardBorderBankTops}.
     *
     * @return true when bank-fit raised dry columns (topology may differ for leveling).
     */
    private static boolean fitWaterToBanksByComponent(
        ColumnPlan[] columns,
        int[] componentOf,
        int[] surfaceGrid,
        int gridSize,
        int minBuildY,
        int maxBuildY
    ) {
        final int bankRadius = 3;
        boolean banksConvertedColumns = false;
        int[] bankRaises = new int[columns.length];
        java.util.Arrays.fill(bankRaises, Integer.MIN_VALUE);
        int[] bankScratch = new int[192];

        int maxId = maxLabel(componentOf);
        int componentCount = Math.max(0, maxId + 1);
        int[] componentCounts = new int[componentCount];
        int[] componentSteep = new int[componentCount];
        long[] componentWaterSum = new long[componentCount];
        int[] componentMinWater = new int[componentCount];
        int[] componentMaxWater = new int[componentCount];
        java.util.Arrays.fill(componentMinWater, Integer.MAX_VALUE);
        java.util.Arrays.fill(componentMaxWater, Integer.MIN_VALUE);
        int[][] componentBanks = new int[componentCount][];
        int[] componentBankCounts = new int[componentCount];
        int[] componentMinBank = new int[componentCount];
        java.util.Arrays.fill(componentMinBank, Integer.MAX_VALUE);
        int[] columnMinBank = new int[columns.length];
        java.util.Arrays.fill(columnMinBank, Integer.MAX_VALUE);

        // Pass A: gather per-column bank samples and component stats.
        for (int index = 0; index < columns.length; index++) {
            ColumnPlan column = columns[index];
            if (!column.river().hasWater()) {
                continue;
            }
            int localZ = index / CHUNK_SIZE;
            int localX = index - localZ * CHUNK_SIZE;
            int waterSurface = column.river().waterSurfaceY();
            int bankCount = 0;
            for (int dz = -bankRadius; dz <= bankRadius; dz++) {
                for (int dx = -bankRadius; dx <= bankRadius; dx++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int nx = localX + dx;
                    int nz = localZ + dz;
                    if (nx < 0 || nz < 0 || nx >= CHUNK_SIZE || nz >= CHUNK_SIZE) {
                        continue;
                    }
                    ColumnPlan neighbor = columns[nz * CHUNK_SIZE + nx];
                    if (neighbor.river().hasWater()) {
                        continue;
                    }
                    if (bankCount < bankScratch.length) {
                        bankScratch[bankCount++] = neighbor.originalSurfaceY();
                    }
                }
            }
            if (surfaceGrid != null && gridSize > 0 && isNearChunkBorder(localX, localZ, 2)) {
                bankCount = appendOutwardBorderBankTops(
                    bankScratch, bankCount, surfaceGrid, gridSize, localX, localZ, waterSurface
                );
            }
            columnMinBank[index] = robustLowBank(bankScratch, bankCount);

            int id = componentOf[index];
            if (id < 0 || id >= componentCount) {
                continue;
            }
            componentCounts[id]++;
            componentWaterSum[id] += waterSurface;
            componentMinWater[id] = Math.min(componentMinWater[id], waterSurface);
            componentMaxWater[id] = Math.max(componentMaxWater[id], waterSurface);
            if (column.steep()) {
                componentSteep[id]++;
            }
            if (bankCount > 0) {
                int[] dest = componentBanks[id];
                int destCount = componentBankCounts[id];
                int needed = destCount + bankCount;
                if (dest == null) {
                    dest = new int[Math.max(64, needed)];
                    componentBanks[id] = dest;
                } else if (needed > dest.length) {
                    dest = java.util.Arrays.copyOf(dest, Math.max(dest.length * 2, needed));
                    componentBanks[id] = dest;
                }
                System.arraycopy(bankScratch, 0, dest, destCount, bankCount);
                componentBankCounts[id] = needed;
            }
        }

        for (int id = 0; id < componentCount; id++) {
            if (componentBankCounts[id] > 0) {
                componentMinBank[id] = robustLowBank(componentBanks[id], componentBankCounts[id]);
            }
        }

        boolean[] mostlySteep = new boolean[componentCount];
        for (int id = 0; id < componentCount; id++) {
            int count = componentCounts[id];
            if (count <= 0) {
                continue;
            }
            int span = componentMaxWater[id] - componentMinWater[id];
            // Wide reaches stay on shared gentle fit even when DEM marks many columns steep.
            mostlySteep[id] = count < 32
                && componentSteep[id] * 2 > count
                && span > 2;
        }

        // Pass B: steep components (or unlabeled) — per-column fit.
        for (int index = 0; index < columns.length; index++) {
            ColumnPlan column = columns[index];
            if (!column.river().hasWater()) {
                continue;
            }
            int id = componentOf[index];
            if (id >= 0 && id < componentCount && !mostlySteep[id]) {
                continue;
            }
            int localZ = index / CHUNK_SIZE;
            int localX = index - localZ * CHUNK_SIZE;
            int waterSurface = column.river().waterSurfaceY();
            int minBank = columnMinBank[index];
            int fitted = RiverBankFit.resolveWaterSurface(waterSurface, minBank);
            fitted = Math.clamp(fitted, minBuildY + 1, maxBuildY);
            if (fitted != waterSurface) {
                UkGeoChunkGenerator.RiverShape river = column.river();
                int depth = Math.max(1, river.waterSurfaceY() - river.terrainSurfaceY());
                int newBed = RiverBankFit.bedForWater(fitted, depth, minBuildY);
                columns[index] = withRiverBedAndWater(column, newBed, fitted, minBuildY, maxBuildY);
                waterSurface = fitted;
            }
            recordBankRaises(columns, bankRaises, localX, localZ, bankRadius, waterSurface, minBank);
        }

        // Pass C: gentle components — water flush with lowest bank; one shared fit for the reach (incl. mid-channel).
        int[] gentleFitted = new int[componentCount];
        java.util.Arrays.fill(gentleFitted, Integer.MIN_VALUE);
        for (int id = 0; id < componentCount; id++) {
            if (componentCounts[id] == 0 || mostlySteep[id]) {
                continue;
            }
            int avgWater = (int) (componentWaterSum[id] / componentCounts[id]);
            // Prefer lowest bank as the shared plane; never seed below banks via min(avg, minWater).
            int fitted = componentMinBank[id] != Integer.MAX_VALUE
                ? componentMinBank[id]
                : avgWater;
            gentleFitted[id] = Math.clamp(fitted, minBuildY + 1, maxBuildY);
        }
        for (int index = 0; index < columns.length; index++) {
            ColumnPlan column = columns[index];
            if (!column.river().hasWater()) {
                continue;
            }
            int id = componentOf[index];
            if (id < 0 || id >= componentCount || mostlySteep[id] || gentleFitted[id] == Integer.MIN_VALUE) {
                continue;
            }
            int localZ = index / CHUNK_SIZE;
            int localX = index - localZ * CHUNK_SIZE;
            int fitted = gentleFitted[id];
            UkGeoChunkGenerator.RiverShape river = column.river();
            if (fitted != river.waterSurfaceY()) {
                int depth = Math.max(1, river.waterSurfaceY() - river.terrainSurfaceY());
                int newBed = RiverBankFit.bedForWater(fitted, depth, minBuildY);
                columns[index] = withRiverBedAndWater(column, newBed, fitted, minBuildY, maxBuildY);
            }
            recordBankRaises(columns, bankRaises, localX, localZ, bankRadius, fitted, componentMinBank[id]);
        }

        // Pass D: apply bank raises to dry columns.
        for (int index = 0; index < columns.length; index++) {
            ColumnPlan column = columns[index];
            if (bankRaises[index] == Integer.MIN_VALUE || column.river().hasWater()) {
                continue;
            }
            int raisedTop = Math.clamp(bankRaises[index], minBuildY + 1, maxBuildY);
            if (raisedTop <= column.terrainTop()) {
                continue;
            }
            UkGeoChunkGenerator.RiverShape river = column.river();
            UkGeoChunkGenerator.RiverShape raisedRiver = new UkGeoChunkGenerator.RiverShape(
                false,
                river.influenced(),
                raisedTop,
                raisedTop,
                river.floorMaterial()
            );
            int columnTop = Math.clamp(Math.max(column.columnTop(), raisedTop), minBuildY, maxBuildY);
            columns[index] = new ColumnPlan(
                column.originalSurfaceY(),
                raisedTop,
                columnTop,
                column.steep(),
                column.coastalBeach(),
                raisedRiver,
                column.vegetationClass(),
                column.surfaceRock(),
                column.exposedSurfaceRock(),
                column.hasHeightData()
            );
            banksConvertedColumns = true;
        }
        return banksConvertedColumns;
    }


    private static void applyBankRaises(ColumnPlan[] columns, int[] bankRaises, int minBuildY, int maxBuildY) {
        for (int index = 0; index < columns.length; index++) {
            ColumnPlan column = columns[index];
            if (bankRaises[index] == Integer.MIN_VALUE || column.river().hasWater()) {
                continue;
            }
            int raisedTop = Math.clamp(bankRaises[index], minBuildY + 1, maxBuildY);
            if (raisedTop <= column.terrainTop()) {
                continue;
            }
            UkGeoChunkGenerator.RiverShape river = column.river();
            UkGeoChunkGenerator.RiverShape raisedRiver = new UkGeoChunkGenerator.RiverShape(
                false,
                river.influenced(),
                raisedTop,
                raisedTop,
                river.floorMaterial()
            );
            int columnTop = Math.clamp(Math.max(column.columnTop(), raisedTop), minBuildY, maxBuildY);
            columns[index] = new ColumnPlan(
                column.originalSurfaceY(),
                raisedTop,
                columnTop,
                column.steep(),
                column.coastalBeach(),
                raisedRiver,
                column.vegetationClass(),
                column.surfaceRock(),
                column.exposedSurfaceRock(),
                column.hasHeightData()
            );
        }
    }

    private static void recordBankRaises(
        ColumnPlan[] columns,
        int[] bankRaises,
        int localX,
        int localZ,
        int bankRadius,
        int waterSurface,
        int minBank
    ) {
        if (minBank == Integer.MAX_VALUE || waterSurface - minBank <= 0 || waterSurface - minBank > RiverBankFit.MAX_BANK_RAISE) {
            return;
        }
        for (int dz = -bankRadius; dz <= bankRadius; dz++) {
            for (int dx = -bankRadius; dx <= bankRadius; dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = localX + dx;
                int nz = localZ + dz;
                if (nx < 0 || nz < 0 || nx >= CHUNK_SIZE || nz >= CHUNK_SIZE) {
                    continue;
                }
                int nIndex = nz * CHUNK_SIZE + nx;
                ColumnPlan neighbor = columns[nIndex];
                if (neighbor.river().hasWater()) {
                    continue;
                }
                int bankTop = neighbor.terrainTop();
                int raised = RiverBankFit.raiseBankToward(bankTop, waterSurface);
                if (raised > bankTop) {
                    int prevRaise = bankRaises[nIndex];
                    bankRaises[nIndex] = prevRaise == Integer.MIN_VALUE ? raised : Math.max(prevRaise, raised);
                }
            }
        }
    }


    /**
     * Level high banks to the low-bank water plane, then conform nearby terrain (carve high, light-raise low within reach).
     * Skips steep cascade/waterfall components.
     */
    private static void carveHighBanksTowardWater(ColumnPlan[] columns, int minBuildY, int maxBuildY) {
        int[] componentOf = labelWaterComponents(columns);
        boolean[] steepCascade = steepCascadeWaterComponents(columns, componentOf);
        int reach = (int) Math.round(RiverBankFit.BANK_CARVE_REACH);

        for (int index = 0; index < columns.length; index++) {
            ColumnPlan column = columns[index];
            if (column.river().hasWater()) {
                continue;
            }
            int terrainTop = column.terrainTop();
            int bestWater = Integer.MIN_VALUE;
            double bestDist = Double.MAX_VALUE;
            int localZ = index / CHUNK_SIZE;
            int localX = index - localZ * CHUNK_SIZE;
            for (int dz = -reach; dz <= reach; dz++) {
                for (int dx = -reach; dx <= reach; dx++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int nx = localX + dx;
                    int nz = localZ + dz;
                    if (nx < 0 || nz < 0 || nx >= CHUNK_SIZE || nz >= CHUNK_SIZE) {
                        continue;
                    }
                    int nIndex = nz * CHUNK_SIZE + nx;
                    ColumnPlan neighbor = columns[nIndex];
                    if (!neighbor.river().hasWater()) {
                        continue;
                    }
                    int id = componentOf[nIndex];
                    if (id >= 0 && steepCascade.length > id && steepCascade[id]) {
                        continue;
                    }
                    double dist = Math.hypot(dx, dz);
                    if (dist > RiverBankFit.BANK_CARVE_REACH) {
                        continue;
                    }
                    int waterY = neighbor.river().waterSurfaceY();
                    if (dist < bestDist || (dist == bestDist && waterY < bestWater)) {
                        bestDist = dist;
                        bestWater = waterY;
                    }
                }
            }
            if (bestWater == Integer.MIN_VALUE) {
                continue;
            }
            int target = RiverBankFit.bankConformTarget(
                column.originalSurfaceY(), bestWater, bestDist, RiverBankFit.BANK_CARVE_REACH
            );
            target = Math.clamp(target, minBuildY + 1, maxBuildY);
            if (target == terrainTop) {
                continue;
            }
            UkGeoChunkGenerator.RiverShape river = column.river();
            UkGeoChunkGenerator.RiverShape carved = new UkGeoChunkGenerator.RiverShape(
                false,
                river.influenced(),
                target,
                bestWater,
                river.floorMaterial()
            );
            int columnTop = Math.clamp(Math.max(column.columnTop(), target), minBuildY, maxBuildY);
            columns[index] = new ColumnPlan(
                column.originalSurfaceY(),
                target,
                columnTop,
                column.steep(),
                column.coastalBeach(),
                carved,
                column.vegetationClass(),
                column.surfaceRock(),
                column.exposedSurfaceRock(),
                column.hasHeightData()
            );
        }
    }

    /**
     * Soften cubic lake beds/water planes: level the water surface per lake component,
     * then diffuse beds over several 8-connected averaging passes.
     */
    private static void smoothFreshwaterLakeBeds(ColumnPlan[] columns, int minBuildY, int maxBuildY) {
        int[] componentOf = labelWaterComponents(columns);
        int maxId = maxLabel(componentOf);
        if (maxId < 0) {
            return;
        }
        int componentCount = maxId + 1;
        int[] waterCounts = new int[componentCount];
        int[] waterMax = new int[componentCount];
        java.util.Arrays.fill(waterMax, Integer.MIN_VALUE);
        for (int index = 0; index < columns.length; index++) {
            ColumnPlan column = columns[index];
            if (!column.river().hasWater()
                || column.vegetationClass() != UkGeoChunkGenerator.VEGETATION_FRESHWATER) {
                continue;
            }
            int id = componentOf[index];
            if (id < 0 || id >= componentCount) {
                continue;
            }
            waterCounts[id]++;
            waterMax[id] = Math.max(waterMax[id], column.river().waterSurfaceY());
        }

        // Flatten stepped lake water planes within each sizable freshwater component.
        for (int index = 0; index < columns.length; index++) {
            ColumnPlan column = columns[index];
            if (!column.river().hasWater()
                || column.vegetationClass() != UkGeoChunkGenerator.VEGETATION_FRESHWATER) {
                continue;
            }
            int id = componentOf[index];
            if (id < 0 || id >= componentCount || waterCounts[id] < LAKE_SMOOTH_MIN_CELLS) {
                continue;
            }
            int targetWater = waterMax[id];
            if (targetWater == Integer.MIN_VALUE || targetWater == column.river().waterSurfaceY()) {
                continue;
            }
            columns[index] = withRiverBedAndWater(
                column,
                column.river().terrainSurfaceY(),
                targetWater,
                minBuildY,
                maxBuildY
            );
        }

        int[] beds = new int[columns.length];
        for (int index = 0; index < columns.length; index++) {
            ColumnPlan column = columns[index];
            if (!column.river().hasWater()
                || column.vegetationClass() != UkGeoChunkGenerator.VEGETATION_FRESHWATER) {
                beds[index] = Integer.MIN_VALUE;
                continue;
            }
            int id = componentOf[index];
            if (id < 0 || id >= componentCount || waterCounts[id] < LAKE_SMOOTH_MIN_CELLS) {
                beds[index] = Integer.MIN_VALUE;
                continue;
            }
            int localZ = index / CHUNK_SIZE;
            int localX = index - localZ * CHUNK_SIZE;
            int freshwaterNeighbors = 0;
            for (int[] offset : EIGHT_OFFSETS) {
                int nx = localX + offset[0];
                int nz = localZ + offset[1];
                if (nx < 0 || nz < 0 || nx >= CHUNK_SIZE || nz >= CHUNK_SIZE) {
                    continue;
                }
                int neighbor = nz * CHUNK_SIZE + nx;
                ColumnPlan nb = columns[neighbor];
                if (nb.river().hasWater()
                    && nb.vegetationClass() == UkGeoChunkGenerator.VEGETATION_FRESHWATER
                    && componentOf[neighbor] == id) {
                    freshwaterNeighbors++;
                }
            }
            // Include near-shore cells so cubic rings dissolve at the edge too.
            if (freshwaterNeighbors < 2) {
                beds[index] = Integer.MIN_VALUE;
            } else {
                beds[index] = column.river().terrainSurfaceY();
            }
        }

        for (int pass = 0; pass < LAKE_BED_SMOOTH_PASSES; pass++) {
            int[] next = beds.clone();
            for (int index = 0; index < columns.length; index++) {
                if (beds[index] == Integer.MIN_VALUE) {
                    continue;
                }
                int localZ = index / CHUNK_SIZE;
                int localX = index - localZ * CHUNK_SIZE;
                int component = componentOf[index];
                int sum = beds[index];
                int count = 1;
                for (int[] offset : EIGHT_OFFSETS) {
                    int nx = localX + offset[0];
                    int nz = localZ + offset[1];
                    if (nx < 0 || nz < 0 || nx >= CHUNK_SIZE || nz >= CHUNK_SIZE) {
                        continue;
                    }
                    int neighbor = nz * CHUNK_SIZE + nx;
                    if (componentOf[neighbor] != component || beds[neighbor] == Integer.MIN_VALUE) {
                        continue;
                    }
                    sum += beds[neighbor];
                    count++;
                }
                int averaged = Math.round(sum / (float) count);
                int bed = beds[index];
                if (averaged > bed + 1) {
                    averaged = bed + 1;
                } else if (averaged < bed - 1) {
                    averaged = bed - 1;
                }
                next[index] = Math.max(minBuildY + 1, averaged);
            }
            beds = next;
        }

        for (int index = 0; index < columns.length; index++) {
            if (beds[index] == Integer.MIN_VALUE) {
                continue;
            }
            ColumnPlan column = columns[index];
            int newBed = beds[index];
            if (newBed == column.river().terrainSurfaceY()) {
                continue;
            }
            columns[index] = withRiverBedAndWater(
                column,
                newBed,
                column.river().waterSurfaceY(),
                minBuildY,
                maxBuildY
            );
        }
    }

    /**
     * Just upstream of a cascade, notch dry banks toward each bank's own baked water Y.
     * Water columns are not edited, and no column's waterSurfaceY is replaced.
     */
    private static void cutCascadeLips(ColumnPlan[] columns, int minBuildY, int maxBuildY) {
        boolean[] isLip = new boolean[columns.length];
        for (int index = 0; index < columns.length; index++) {
            ColumnPlan column = columns[index];
            if (!column.river().hasWater()) {
                continue;
            }
            int waterY = column.river().waterSurfaceY();
            int localZ = index / CHUNK_SIZE;
            int localX = index - localZ * CHUNK_SIZE;
            for (int[] offset : CARDINAL_OFFSETS) {
                int nx = localX + offset[0];
                int nz = localZ + offset[1];
                if (nx < 0 || nz < 0 || nx >= CHUNK_SIZE || nz >= CHUNK_SIZE) {
                    continue;
                }
                ColumnPlan neighbor = columns[nz * CHUNK_SIZE + nx];
                if (neighbor.river().hasWater() && neighbor.river().waterSurfaceY() <= waterY - 1) {
                    isLip[index] = true;
                    break;
                }
            }
        }

        final int lipCarveRadius = 3;
        for (int index = 0; index < columns.length; index++) {
            if (!isLip[index]) {
                continue;
            }
            int localZ = index / CHUNK_SIZE;
            int localX = index - localZ * CHUNK_SIZE;
            for (int dz = -lipCarveRadius; dz <= lipCarveRadius; dz++) {
                for (int dx = -lipCarveRadius; dx <= lipCarveRadius; dx++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    if (Math.abs(dx) + Math.abs(dz) > lipCarveRadius) {
                        continue;
                    }
                    int nx = localX + dx;
                    int nz = localZ + dz;
                    if (nx < 0 || nz < 0 || nx >= CHUNK_SIZE || nz >= CHUNK_SIZE) {
                        continue;
                    }
                    notchBankToOwnWater(columns, nz * CHUNK_SIZE + nx, Math.hypot(dx, dz), lipCarveRadius, minBuildY, maxBuildY);
                }
            }
        }

        final int brookCarveRadius = 2;
        for (int index = 0; index < columns.length; index++) {
            ColumnPlan column = columns[index];
            if (!column.steep() || !column.river().hasWater()) {
                continue;
            }
            int localZ = index / CHUNK_SIZE;
            int localX = index - localZ * CHUNK_SIZE;
            for (int dz = -brookCarveRadius; dz <= brookCarveRadius; dz++) {
                for (int dx = -brookCarveRadius; dx <= brookCarveRadius; dx++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    if (Math.abs(dx) + Math.abs(dz) > brookCarveRadius) {
                        continue;
                    }
                    int nx = localX + dx;
                    int nz = localZ + dz;
                    if (nx < 0 || nz < 0 || nx >= CHUNK_SIZE || nz >= CHUNK_SIZE) {
                        continue;
                    }
                    notchBankToOwnWater(columns, nz * CHUNK_SIZE + nx, Math.hypot(dx, dz), brookCarveRadius, minBuildY, maxBuildY);
                }
            }
        }
    }

    /** Lower a dry bank toward its own baked water Y. Does not create water or change water Y. */
    private static void notchBankToOwnWater(
        ColumnPlan[] columns,
        int nIndex,
        double dOut,
        double reach,
        int minBuildY,
        int maxBuildY
    ) {
        ColumnPlan neighbor = columns[nIndex];
        UkGeoChunkGenerator.RiverShape river = neighbor.river();
        if (river.hasWater() || !river.influenced()) {
            return;
        }
        int waterY = river.waterSurfaceY();
        int bankTop = Math.min(neighbor.terrainTop(), neighbor.originalSurfaceY());
        if (bankTop <= waterY) {
            return;
        }
        int notchedTop = RiverBankFit.bankCarveTarget(bankTop, waterY, dOut, Math.max(1.0, reach));
        notchedTop = Math.clamp(notchedTop, Math.max(minBuildY + 1, waterY), bankTop);
        if (notchedTop >= neighbor.terrainTop()) {
            return;
        }
        UkGeoChunkGenerator.RiverShape notched = new UkGeoChunkGenerator.RiverShape(
            false,
            true,
            notchedTop,
            waterY,
            river.floorMaterial()
        );
        int columnTop = Math.clamp(Math.max(neighbor.columnTop(), notchedTop), minBuildY, maxBuildY);
        columns[nIndex] = new ColumnPlan(
            neighbor.originalSurfaceY(),
            notchedTop,
            columnTop,
            neighbor.steep(),
            neighbor.coastalBeach(),
            notched,
            neighbor.vegetationClass(),
            neighbor.surfaceRock(),
            neighbor.exposedSurfaceRock(),
            neighbor.hasHeightData()
        );
    }

    private static int maxLabel(int[] labels) {
        int max = -1;
        for (int id : labels) {
            max = Math.max(max, id);
        }
        return max;
    }

    /**
     * Light 8-neighbour average on dry river-influenced bank tops to break triangular stamp facets.
     * Does not edit wet columns or baked water Y.
     */
    private static void smoothDryRiverBanks(ColumnPlan[] columns, int minBuildY, int maxBuildY) {
        int[] tops = new int[columns.length];
        boolean[] editable = new boolean[columns.length];
        for (int index = 0; index < columns.length; index++) {
            ColumnPlan column = columns[index];
            UkGeoChunkGenerator.RiverShape river = column.river();
            if (!river.influenced() || river.hasWater()) {
                tops[index] = Integer.MIN_VALUE;
                continue;
            }
            tops[index] = column.terrainTop();
            editable[index] = true;
        }
        for (int pass = 0; pass < 2; pass++) {
            int[] next = tops.clone();
            for (int index = 0; index < columns.length; index++) {
                if (!editable[index]) {
                    continue;
                }
                int localZ = index / CHUNK_SIZE;
                int localX = index - localZ * CHUNK_SIZE;
                int sum = tops[index];
                int count = 1;
                for (int[] offset : EIGHT_OFFSETS) {
                    int nx = localX + offset[0];
                    int nz = localZ + offset[1];
                    if (nx < 0 || nz < 0 || nx >= CHUNK_SIZE || nz >= CHUNK_SIZE) {
                        continue;
                    }
                    int neighbor = nz * CHUNK_SIZE + nx;
                    if (!editable[neighbor]) {
                        continue;
                    }
                    sum += tops[neighbor];
                    count++;
                }
                int averaged = Math.round(sum / (float) count);
                int top = tops[index];
                if (averaged > top + 1) {
                    averaged = top + 1;
                } else if (averaged < top - 1) {
                    averaged = top - 1;
                }
                // Never carve further below the original DEM than the bank cap allows.
                int floor = columns[index].originalSurfaceY() - RiverBankFit.MAX_BANK_INCISION_NEAR;
                next[index] = Math.max(minBuildY + 1, Math.max(averaged, floor));
            }
            tops = next;
        }
        for (int index = 0; index < columns.length; index++) {
            if (!editable[index]) {
                continue;
            }
            ColumnPlan column = columns[index];
            int newTop = tops[index];
            if (newTop == column.terrainTop()) {
                continue;
            }
            UkGeoChunkGenerator.RiverShape river = column.river();
            UkGeoChunkGenerator.RiverShape shaped = new UkGeoChunkGenerator.RiverShape(
                false,
                true,
                newTop,
                river.waterSurfaceY(),
                river.floorMaterial()
            );
            int columnTop = Math.clamp(Math.max(column.columnTop(), newTop), minBuildY, maxBuildY);
            columns[index] = new ColumnPlan(
                column.originalSurfaceY(),
                newTop,
                columnTop,
                column.steep(),
                column.coastalBeach(),
                shaped,
                column.vegetationClass(),
                column.surfaceRock(),
                column.exposedSurfaceRock(),
                column.hasHeightData()
            );
        }
    }

    private static final int[][] CARDINAL_OFFSETS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] EIGHT_OFFSETS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1},
        {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
    private static final int RIVER_BED_MAX_NEIGHBOR_STEP = 1;
    private static final int RIVER_BED_SMOOTH_PASSES = 3;
    private static final int LAKE_BED_SMOOTH_PASSES = 4;
    private static final int LAKE_SMOOTH_MIN_CELLS = 8;

    /** 4-connected flood-fill labels for hasWater columns; -1 = dry / no water. */
    private static int[] labelWaterComponents(ColumnPlan[] columns) {
        int[] componentOf = new int[columns.length];
        java.util.Arrays.fill(componentOf, -1);
        int[] queue = new int[columns.length];
        int nextId = 0;
        for (int seed = 0; seed < columns.length; seed++) {
            if (!columns[seed].river().hasWater() || componentOf[seed] >= 0) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = seed;
            componentOf[seed] = nextId;
            while (head < tail) {
                int index = queue[head++];
                int localZ = index / CHUNK_SIZE;
                int localX = index - localZ * CHUNK_SIZE;
                for (int[] offset : CARDINAL_OFFSETS) {
                    int nx = localX + offset[0];
                    int nz = localZ + offset[1];
                    if (nx < 0 || nz < 0 || nx >= CHUNK_SIZE || nz >= CHUNK_SIZE) {
                        continue;
                    }
                    int neighbor = nz * CHUNK_SIZE + nx;
                    if (componentOf[neighbor] >= 0 || !columns[neighbor].river().hasWater()) {
                        continue;
                    }
                    componentOf[neighbor] = nextId;
                    queue[tail++] = neighbor;
                }
            }
            nextId++;
        }
        return componentOf;
    }

    private static void smoothRiverBedsInComponents(ColumnPlan[] columns, int minBuildY, int maxBuildY) {
        smoothRiverBedsInComponents(columns, labelWaterComponents(columns), minBuildY, maxBuildY);
    }

    /**
     * Limit |bed[i]-bed[neighbour]| within each water component so mid-channel ridges/stairs vanish.
     */
    private static void smoothRiverBedsInComponents(ColumnPlan[] columns, int[] componentOf, int minBuildY, int maxBuildY) {
        int[] beds = new int[columns.length];
        int componentCount = 0;
        for (int index = 0; index < columns.length; index++) {
            if (columns[index].river().hasWater()) {
                beds[index] = columns[index].river().terrainSurfaceY();
                componentCount = Math.max(componentCount, componentOf[index] + 1);
            } else {
                beds[index] = Integer.MIN_VALUE;
            }
        }
        int[] waterCounts = new int[componentCount];
        long[] depthSums = new long[componentCount];
        for (int index = 0; index < columns.length; index++) {
            if (beds[index] == Integer.MIN_VALUE) {
                continue;
            }
            int id = componentOf[index];
            if (id < 0 || id >= componentCount) {
                continue;
            }
            UkGeoChunkGenerator.RiverShape river = columns[index].river();
            waterCounts[id]++;
            depthSums[id] += Math.max(1, river.waterSurfaceY() - river.terrainSurfaceY());
        }
        int[] passLimit = new int[componentCount];
        int maxPasses = 1;
        for (int id = 0; id < componentCount; id++) {
            if (waterCounts[id] == 0) {
                passLimit[id] = 0;
                continue;
            }
            double avgDepth = depthSums[id] / (double) waterCounts[id];
            int passes = (waterCounts[id] < 12 || avgDepth <= 2.5) ? 1 : RIVER_BED_SMOOTH_PASSES;
            passLimit[id] = passes;
            maxPasses = Math.max(maxPasses, passes);
        }
        for (int pass = 0; pass < maxPasses; pass++) {
            int[] next = beds.clone();
            for (int index = 0; index < columns.length; index++) {
                if (beds[index] == Integer.MIN_VALUE) {
                    continue;
                }
                int component = componentOf[index];
                if (component < 0 || component >= componentCount || pass >= passLimit[component]) {
                    continue;
                }
                int localZ = index / CHUNK_SIZE;
                int localX = index - localZ * CHUNK_SIZE;
                int bed = beds[index];
                for (int[] offset : CARDINAL_OFFSETS) {
                    int nx = localX + offset[0];
                    int nz = localZ + offset[1];
                    if (nx < 0 || nz < 0 || nx >= CHUNK_SIZE || nz >= CHUNK_SIZE) {
                        continue;
                    }
                    int neighbor = nz * CHUNK_SIZE + nx;
                    if (componentOf[neighbor] != component || beds[neighbor] == Integer.MIN_VALUE) {
                        continue;
                    }
                    int neighborBed = beds[neighbor];
                    if (bed > neighborBed + RIVER_BED_MAX_NEIGHBOR_STEP) {
                        bed = neighborBed + RIVER_BED_MAX_NEIGHBOR_STEP;
                    } else if (bed < neighborBed - RIVER_BED_MAX_NEIGHBOR_STEP) {
                        bed = neighborBed - RIVER_BED_MAX_NEIGHBOR_STEP;
                    }
                }
                next[index] = Math.max(minBuildY + 1, bed);
            }
            beds = next;
        }
        for (int index = 0; index < columns.length; index++) {
            if (beds[index] == Integer.MIN_VALUE) {
                continue;
            }
            ColumnPlan column = columns[index];
            UkGeoChunkGenerator.RiverShape river = column.river();
            int waterY = river.waterSurfaceY();
            int newBed = Math.min(beds[index], waterY - 1);
            newBed = Math.max(minBuildY + 1, newBed);
            if (newBed == river.terrainSurfaceY()) {
                continue;
            }
            // Beds only: copy the baked water Y and never raise it to follow the bed.
            UkGeoChunkGenerator.RiverShape shaped = new UkGeoChunkGenerator.RiverShape(
                true,
                river.influenced(),
                newBed,
                waterY,
                river.floorMaterial()
            );
            int columnTop = Math.clamp(Math.max(column.columnTop(), waterY), minBuildY, maxBuildY);
            columns[index] = new ColumnPlan(
                column.originalSurfaceY(),
                newBed,
                columnTop,
                column.steep(),
                column.coastalBeach(),
                shaped,
                column.vegetationClass(),
                column.surfaceRock(),
                column.exposedSurfaceRock(),
                column.hasHeightData()
            );
        }
    }

    /**
     * Move water/bed together while preserving column depth.
     * Callers pass depth from waterSurfaceY - terrainSurfaceY; that depth already encodes the bowl profile.
     */
    private static ColumnPlan withRiverBedAndWater(
        ColumnPlan column,
        int newBed,
        int waterY,
        int minBuildY,
        int maxBuildY
    ) {
        UkGeoChunkGenerator.RiverShape river = column.river();
        waterY = Math.max(waterY, newBed + 1);
        UkGeoChunkGenerator.RiverShape shaped = new UkGeoChunkGenerator.RiverShape(
            true,
            river.influenced(),
            newBed,
            waterY,
            river.floorMaterial()
        );
        int columnTop = Math.clamp(Math.max(column.columnTop(), waterY), minBuildY, maxBuildY);
        return new ColumnPlan(
            column.originalSurfaceY(),
            newBed,
            columnTop,
            column.steep(),
            column.coastalBeach(),
            shaped,
            column.vegetationClass(),
            column.surfaceRock(),
            column.exposedSurfaceRock(),
            column.hasHeightData()
        );
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
        applyOres(plan, chunk, null, null);
    }

    private static void applyOres(Plan plan, ChunkAccess chunk, LevelChunkSection[] sectionCache, SectionWriteCursor sectionCursor) {
        long startNanos = System.nanoTime();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        LevelChunkSection[] cache = sectionCache;
        SectionWriteCursor writeCursor = sectionCursor;
        if (FAST_SECTION_WRITES && cache == null) {
            cache = new LevelChunkSection[chunk.getSectionsCount()];
            writeCursor = new SectionWriteCursor();
        }
        for (OrePlacement placement : plan.orePlacements) {
            ColumnPlan column = plan.columns[placement.localZ() * CHUNK_SIZE + placement.localX()];
            if (isProtectedWaterColumn(column, placement.y(), plan.seaLevelY())) {
                continue;
            }
            BlockState current = chunk.getBlockState(cursor.set(placement.localX(), placement.y(), placement.localZ()));
            if (current.is(Blocks.STONE) || current.is(Blocks.DEEPSLATE)) {
                if (hasAirOrWaterNeighbor(chunk, cursor, placement.localX(), placement.y(), placement.localZ())) {
                    continue;
                }
                setBlock(chunk, cursor, cache, writeCursor, placement.localX(), placement.y(), placement.localZ(), placement.state());
            }
        }
        logTiming("ChunkTerrainPlanner.applyOres", chunk, startNanos);
    }


    private static boolean hasAirOrWaterNeighbor(ChunkAccess chunk, BlockPos.MutableBlockPos cursor, int localX, int y, int localZ) {
        int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        int buildMin = chunk.getMinBuildHeight();
        int buildMax = chunk.getMaxBuildHeight();
        for (int[] offset : offsets) {
            int nx = localX + offset[0];
            int ny = y + offset[1];
            int nz = localZ + offset[2];
            if (nx < 0 || nx >= 16 || nz < 0 || nz >= 16 || ny < buildMin || ny >= buildMax) {
                continue;
            }
            BlockState neighbor = chunk.getBlockState(cursor.set(nx, ny, nz));
            if (neighbor.isAir() || neighbor.getFluidState().is(Fluids.WATER)) {
                return true;
            }
        }
        return false;
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
        LevelChunkSection[] sectionCache,
        SectionWriteCursor sectionCursor,
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

        setBlock(chunk, cursor, sectionCache, sectionCursor, localX, minBuildY, localZ, Blocks.BEDROCK.defaultBlockState());
        int stoneTop = Math.max(minBuildY + 1, terrainTop - 12);
        for (int y = minBuildY + 1; y < stoneTop; y++) {
            boolean allowCave = !steep || y < terrainTop - 8;
            CaveState caveState = y <= terrainTop && allowCave && caveMask.mayCarveAtY(y, vanillaTop)
                ? caveState(chunk, cursor, caveMask, localX, y, localZ, worldX, worldZ, vanillaTop, terrainTop)
                : CaveState.SOLID;
            if (caveState == CaveState.AIR) {
                if (isProtectedWaterCave(protection, y)) {
                    setBlock(chunk, cursor, sectionCache, sectionCursor, localX, y, localZ, protectedWaterState(column, protection, y, minBuildY, surfaceRock, steep, river, originalSurfaceY, vegetationClass, seaLevelY));
                    continue;
                }
                setBlock(chunk, cursor, sectionCache, sectionCursor, localX, y, localZ, Blocks.AIR.defaultBlockState());
                continue;
            } else if (caveState == CaveState.LAVA) {
                if (isProtectedWaterCave(protection, y)) {
                    setBlock(chunk, cursor, sectionCache, sectionCursor, localX, y, localZ, protectedWaterState(column, protection, y, minBuildY, surfaceRock, steep, river, originalSurfaceY, vegetationClass, seaLevelY));
                    continue;
                }
                setBlock(chunk, cursor, sectionCache, sectionCursor, localX, y, localZ, Blocks.LAVA.defaultBlockState());
                continue;
            }
            setBlock(chunk, cursor, sectionCache, sectionCursor, localX, y, localZ, y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState());
        }
        for (int y = stoneTop; y <= clearTop; y++) {
            boolean allowCave = !steep || y < terrainTop - 8;
            CaveState caveState = y <= terrainTop && allowCave && caveMask.mayCarveAtY(y, vanillaTop)
                ? caveState(chunk, cursor, caveMask, localX, y, localZ, worldX, worldZ, vanillaTop, terrainTop)
                : CaveState.SOLID;
            if (caveState == CaveState.AIR) {
                if (isProtectedWaterCave(protection, y)) {
                    setBlock(chunk, cursor, sectionCache, sectionCursor, localX, y, localZ, protectedWaterState(column, protection, y, minBuildY, surfaceRock, steep, river, originalSurfaceY, vegetationClass, seaLevelY));
                    continue;
                }
                setBlock(chunk, cursor, sectionCache, sectionCursor, localX, y, localZ, Blocks.AIR.defaultBlockState());
                continue;
            } else if (caveState == CaveState.LAVA) {
                if (isProtectedWaterCave(protection, y)) {
                    setBlock(chunk, cursor, sectionCache, sectionCursor, localX, y, localZ, protectedWaterState(column, protection, y, minBuildY, surfaceRock, steep, river, originalSurfaceY, vegetationClass, seaLevelY));
                    continue;
                }
                setBlock(chunk, cursor, sectionCache, sectionCursor, localX, y, localZ, Blocks.LAVA.defaultBlockState());
                continue;
            }
            BlockState state = columnStateFor(y, terrainTop, minBuildY, surfaceRock, exposedSurfaceRock, steep, river, originalSurfaceY, coastalBeach, vegetationClass, seaLevelY);
            setBlock(chunk, cursor, sectionCache, sectionCursor, localX, y, localZ, state);
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
                boolean ownWater = ownSurface != Integer.MIN_VALUE;
                int waterSurfaceY = ownSurface;
                int floorY = ownWater ? column.terrainTop() : Integer.MIN_VALUE;
                // Do not raise a column's water to a taller neighbour (that created mid-stream humps).
                // Only borrow neighbour water level for dry bank columns that need bed protection.
                if (!ownWater) {
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = localX + dx;
                            int nz = localZ + dz;
                            if (nx < 0 || nz < 0 || nx >= CHUNK_SIZE || nz >= CHUNK_SIZE || (dx == 0 && dz == 0)) {
                                continue;
                            }
                            ColumnPlan neighbor = plan.columns[nz * CHUNK_SIZE + nx];
                            int neighborSurface = plannedWaterSurfaceY(neighbor, plan.seaLevelY());
                            if (neighborSurface == Integer.MIN_VALUE) {
                                continue;
                            }
                            waterSurfaceY = waterSurfaceY == Integer.MIN_VALUE
                                ? neighborSurface
                                : Math.min(waterSurfaceY, neighborSurface);
                            floorY = floorY == Integer.MIN_VALUE
                                ? neighbor.terrainTop()
                                : Math.min(floorY, neighbor.terrainTop());
                        }
                    }
                }
                boolean foundWater = waterSurfaceY != Integer.MIN_VALUE;
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
            // Restricted minerals only via coherent patches (exposedSurfaceRock).
            return exposedSurfaceRock;
        }
        if (steep && y >= surfaceY - 4) {
            return exposedSurfaceRock.is(Blocks.GRASS_BLOCK) ? surfaceRock : exposedSurfaceRock;
        }
        if (y == surfaceY) {
            // Rare coherent mineral patches may break the grass cover; otherwise grass.
            if (!exposedSurfaceRock.is(Blocks.GRASS_BLOCK) && isRestrictedSurfaceOre(surfaceRock)) {
                return exposedSurfaceRock;
            }
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

    /**
     * Spatially coherent exposure patches for restricted surface minerals.
     * Uses smooth value noise so exposures form small regions rather than
     * isolated single-column speckles. Threshold keeps coverage near ~5%.
     */
    private static boolean allowRareExposedSurfaceOre(int worldX, int worldZ, BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return SurfaceExposureNoise.allowExposure(worldX, worldZ, id.hashCode());
    }


    private static void setBlock(
        ChunkAccess chunk,
        BlockPos.MutableBlockPos cursor,
        int localX,
        int y,
        int localZ,
        BlockState state
    ) {
        setBlock(chunk, cursor, null, null, localX, y, localZ, state);
    }

    private static void setBlock(
        ChunkAccess chunk,
        BlockPos.MutableBlockPos cursor,
        LevelChunkSection[] sectionCache,
        SectionWriteCursor sectionCursor,
        int localX,
        int y,
        int localZ,
        BlockState state
    ) {
        if (FAST_SECTION_WRITES && !chunk.isOutsideBuildHeight(y)) {
            int sectionIndex = chunk.getSectionIndex(y);
            LevelChunkSection section;
            if (sectionCursor != null) {
                section = sectionCursor.sectionFor(chunk, sectionCache, sectionIndex);
            } else if (sectionCache != null) {
                section = sectionCache[sectionIndex];
                if (section == null) {
                    section = chunk.getSection(sectionIndex);
                    sectionCache[sectionIndex] = section;
                }
            } else {
                section = chunk.getSection(sectionIndex);
            }
            section.setBlockState(localX, y & 15, localZ, state, false);
            return;
        }
        chunk.setBlockState(cursor.set(localX, y, localZ), state, false);
    }

    /** Reuses the current LevelChunkSection across consecutive Y writes in a column. */
    private static final class SectionWriteCursor {
        private int sectionIndex = Integer.MIN_VALUE;
        private LevelChunkSection section;

        LevelChunkSection sectionFor(ChunkAccess chunk, LevelChunkSection[] sectionCache, int index) {
            if (index == sectionIndex && section != null) {
                return section;
            }
            sectionIndex = index;
            if (sectionCache != null) {
                section = sectionCache[index];
                if (section == null) {
                    section = chunk.getSection(index);
                    sectionCache[index] = section;
                }
            } else {
                section = chunk.getSection(index);
            }
            return section;
        }
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
