package com.ukgeo.worldgen;

import java.io.IOException;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Precomputes per-column terrain for one chunk on a background thread, then applies blocks in bulk.
 */
final class ChunkTerrainPlanner {
    private static final int CHUNK_SIZE = 16;
    private static final int BORDER = 4;
    private static final int WATERBED_PROTECTION_DEPTH = 6;

    private ChunkTerrainPlanner() {
    }

    static Plan compute(UkGeoChunkGenerator generator, UkGeoChunkGenerator.RuntimeData data, ChunkAccess chunk) throws IOException {
        int chunkMinX = chunk.getPos().getMinBlockX();
        int chunkMinZ = chunk.getPos().getMinBlockZ();
        int minBuildY = chunk.getMinBuildHeight();
        int maxBuildY = chunk.getMaxBuildHeight() - 1;
        int margin = Math.max(generator.sampleMargin(), BORDER);
        HeightTileWindow heightWindow = HeightTileWindow.forChunk(data.height(), data.manifest(), chunkMinX, chunkMinZ, margin);

        int gridSize = CHUNK_SIZE + BORDER * 2;
        int[] surfaceGrid = new int[gridSize * gridSize];
        for (int gz = 0; gz < gridSize; gz++) {
            int worldZ = chunkMinZ + gz - BORDER;
            for (int gx = 0; gx < gridSize; gx++) {
                int worldX = chunkMinX + gx - BORDER;
                surfaceGrid[gz * gridSize + gx] = generator.computeSurfaceY(data, heightWindow, worldX, worldZ);
            }
        }

        ColumnPlan[] columns = new ColumnPlan[CHUNK_SIZE * CHUNK_SIZE];
        UkGeoChunkGenerator.WaterShapeCache waterShapeCache = new UkGeoChunkGenerator.WaterShapeCache();
        for (int localZ = 0; localZ < CHUNK_SIZE; localZ++) {
            for (int localX = 0; localX < CHUNK_SIZE; localX++) {
                int worldX = chunkMinX + localX;
                int worldZ = chunkMinZ + localZ;
                int index = localZ * CHUNK_SIZE + localX;
                int surfaceY = surfaceGrid[(localZ + BORDER) * gridSize + (localX + BORDER)];
                boolean steep = isSteep(surfaceGrid, gridSize, localX + BORDER, localZ + BORDER);
                int vegetationClass = generator.sampleVegetationClass(data, worldX, worldZ);
                UkGeoChunkGenerator.RiverShape river = generator.computeSurfaceWaterShape(data, heightWindow, worldX, worldZ, surfaceY, minBuildY, vegetationClass, waterShapeCache);
                int terrainTop = river.terrainSurfaceY();
                int top = Math.clamp(surfaceY, minBuildY + 1, maxBuildY);
                int columnTop = Math.clamp(
                    Math.max(Math.max(top, generator.seaLevel()), Math.max(terrainTop + 1, river.waterSurfaceY())),
                    minBuildY,
                    maxBuildY
                );
                BlockState surfaceRock = generator.sampleSurfaceRock(data, worldX, worldZ, terrainTop);
                columns[index] = new ColumnPlan(top, terrainTop, columnTop, steep, river, vegetationClass, surfaceRock);
            }
        }
        return new Plan(columns, generator.buildOrePlacements(data, chunk, columns), generator.seaLevel());
    }

    static void apply(Plan plan, ChunkAccess chunk, CaveMask caveMask) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        Heightmap ocean = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        int minBuildY = chunk.getMinBuildHeight();
        int chunkMinX = chunk.getPos().getMinBlockX();
        int chunkMinZ = chunk.getPos().getMinBlockZ();
        WaterProtection[] waterProtections = waterProtections(plan);
        for (int localZ = 0; localZ < CHUNK_SIZE; localZ++) {
            for (int localX = 0; localX < CHUNK_SIZE; localX++) {
                ColumnPlan column = plan.columns[localZ * CHUNK_SIZE + localX];
                WaterProtection protection = waterProtections[localZ * CHUNK_SIZE + localX];
                int vanillaTop = caveMask.usesDelegate() ? surface.getHighestTaken(localX, localZ) : minBuildY;
                fillColumn(
                    chunk,
                    cursor,
                    ocean,
                    surface,
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
            }
        }
    }

    static void enforceWaterColumns(Plan plan, ChunkAccess chunk) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        Heightmap ocean = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
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
                    setBlock(chunk, cursor, ocean, surface, localX, y, localZ, state);
                }
                clearWaterAboveSurface(chunk, cursor, localX, localZ, waterSurfaceY);
            }
        }
    }

    private static void clearUnplannedSurfaceWater(ChunkAccess chunk, BlockPos.MutableBlockPos cursor, int localX, int localZ, int terrainTop) {
        int fromY = Math.max(chunk.getMinBuildHeight(), terrainTop + 1);
        int toY = Math.min(chunk.getMaxBuildHeight() - 1, terrainTop + 4);
        for (int y = fromY; y <= toY; y++) {
            if (chunk.getBlockState(cursor.set(localX, y, localZ)).is(Blocks.WATER)) {
                chunk.setBlockState(cursor, Blocks.AIR.defaultBlockState(), false);
            }
        }
    }

    private static void clearWaterAboveSurface(ChunkAccess chunk, BlockPos.MutableBlockPos cursor, int localX, int localZ, int waterSurfaceY) {
        int fromY = Math.max(chunk.getMinBuildHeight(), waterSurfaceY + 1);
        int toY = Math.min(chunk.getMaxBuildHeight() - 1, waterSurfaceY + 3);
        for (int y = fromY; y <= toY; y++) {
            if (chunk.getBlockState(cursor.set(localX, y, localZ)).is(Blocks.WATER)) {
                chunk.setBlockState(cursor, Blocks.AIR.defaultBlockState(), false);
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
            column.steep(),
            column.river(),
            column.originalSurfaceY(),
            column.vegetationClass(),
            seaLevelY
        );
    }

    static void applyOres(Plan plan, ChunkAccess chunk) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (OrePlacement placement : plan.orePlacements) {
            ColumnPlan column = plan.columns[placement.localZ() * CHUNK_SIZE + placement.localX()];
            if (isProtectedWaterColumn(column, placement.y(), plan.seaLevelY())) {
                continue;
            }
            BlockState current = chunk.getBlockState(cursor.set(placement.localX(), placement.y(), placement.localZ()));
            if (current.is(Blocks.STONE) || current.is(Blocks.DEEPSLATE)) {
                chunk.setBlockState(cursor, placement.state(), false);
            }
        }
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

    private static double smoothstep(double value) {
        double t = Math.clamp(value, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static void fillColumn(
        ChunkAccess chunk,
        BlockPos.MutableBlockPos cursor,
        Heightmap ocean,
        Heightmap surface,
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
        int clearTop = caveMask.usesDelegate() ? Math.max(columnTop, vanillaTop) : columnTop;
        UkGeoChunkGenerator.RiverShape river = column.river();
        BlockState surfaceRock = column.surfaceRock();
        boolean steep = column.steep();
        int originalSurfaceY = column.originalSurfaceY();
        int vegetationClass = column.vegetationClass();

        setBlock(chunk, cursor, ocean, surface, localX, minBuildY, localZ, Blocks.BEDROCK.defaultBlockState());
        int stoneTop = Math.max(minBuildY + 1, terrainTop - 12);
        for (int y = minBuildY + 1; y < stoneTop; y++) {
            CaveState caveState = caveState(chunk, cursor, caveMask, localX, y, localZ, worldX, worldZ, vanillaTop, terrainTop);
            if (caveState == CaveState.AIR) {
                if (isProtectedWaterCave(protection, y)) {
                    setBlock(chunk, cursor, ocean, surface, localX, y, localZ, protectedWaterState(column, protection, y, minBuildY, surfaceRock, steep, river, originalSurfaceY, vegetationClass, seaLevelY));
                    continue;
                }
                setBlock(chunk, cursor, ocean, surface, localX, y, localZ, Blocks.AIR.defaultBlockState());
                continue;
            } else if (caveState == CaveState.LAVA) {
                if (isProtectedWaterCave(protection, y)) {
                    setBlock(chunk, cursor, ocean, surface, localX, y, localZ, protectedWaterState(column, protection, y, minBuildY, surfaceRock, steep, river, originalSurfaceY, vegetationClass, seaLevelY));
                    continue;
                }
                setBlock(chunk, cursor, ocean, surface, localX, y, localZ, Blocks.LAVA.defaultBlockState());
                continue;
            }
            setBlock(chunk, cursor, ocean, surface, localX, y, localZ, y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState());
        }
        for (int y = stoneTop; y <= clearTop; y++) {
            CaveState caveState = caveState(chunk, cursor, caveMask, localX, y, localZ, worldX, worldZ, vanillaTop, terrainTop);
            if (caveState == CaveState.AIR) {
                if (isProtectedWaterCave(protection, y)) {
                    setBlock(chunk, cursor, ocean, surface, localX, y, localZ, protectedWaterState(column, protection, y, minBuildY, surfaceRock, steep, river, originalSurfaceY, vegetationClass, seaLevelY));
                    continue;
                }
                setBlock(chunk, cursor, ocean, surface, localX, y, localZ, Blocks.AIR.defaultBlockState());
                continue;
            } else if (caveState == CaveState.LAVA) {
                if (isProtectedWaterCave(protection, y)) {
                    setBlock(chunk, cursor, ocean, surface, localX, y, localZ, protectedWaterState(column, protection, y, minBuildY, surfaceRock, steep, river, originalSurfaceY, vegetationClass, seaLevelY));
                    continue;
                }
                setBlock(chunk, cursor, ocean, surface, localX, y, localZ, Blocks.LAVA.defaultBlockState());
                continue;
            }
            BlockState state = columnStateFor(y, terrainTop, minBuildY, surfaceRock, steep, river, originalSurfaceY, vegetationClass, seaLevelY);
            setBlock(chunk, cursor, ocean, surface, localX, y, localZ, state);
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
        BlockState planned = columnStateFor(y, column.terrainTop(), minBuildY, surfaceRock, steep, river, originalSurfaceY, vegetationClass, seaLevelY);
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
        /*
         * Vertical cave split:
         * - the vanilla delegate is sampled only in its valid Y range, from delegateMinY upward;
         * - an overlap around delegateMinY combines delegate cave air with the custom deep mask;
         * - the custom deep mask owns world Y below delegateMinY down to the real world min Y;
         * - delegate water/lava is ignored as fluid, while custom lava is placed later and is relative
         *   to the actual world min Y instead of vanilla's old hardcoded lava level.
         */
        boolean delegateCave = false;
        if (caveMask.canSampleDelegate(y) && y <= vanillaTop) {
            BlockState existing = chunk.getBlockState(cursor.set(localX, y, localZ));
            delegateCave = existing.isAir() || existing.is(Blocks.LAVA);
        }

        boolean deepCave = caveMask.isDeepCave(worldX, y, worldZ);
        boolean carve = switch (caveMask.mode(y)) {
            case DELEGATE -> delegateCave;
            case TRANSITION -> deepCave || (delegateCave && caveMask.keepDelegateTransitionCave(worldX, y, worldZ));
            case DEEP -> deepCave;
            case SOLID -> false;
        };
        if (!carve) {
            return CaveState.SOLID;
        }
        if (deepCave && caveMask.isDeepLavaFloor(chunk, cursor, localX, y, localZ, worldX, worldZ)) {
            return CaveState.LAVA;
        }
        return CaveState.AIR;
    }

    static BlockState columnStateFor(
        int y,
        int surfaceY,
        int minBuildY,
        BlockState surfaceRock,
        boolean steep,
        UkGeoChunkGenerator.RiverShape river,
        int originalSurfaceY,
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
        if (surfaceY <= seaLevelY + 2 && y >= surfaceY - 2) {
            return Blocks.SAND.defaultBlockState();
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
        if (y >= surfaceY - 12) {
            return surfaceRock;
        }
        return y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }

    private static void setBlock(
        ChunkAccess chunk,
        BlockPos.MutableBlockPos cursor,
        Heightmap ocean,
        Heightmap surface,
        int localX,
        int y,
        int localZ,
        BlockState state
    ) {
        chunk.setBlockState(cursor.set(localX, y, localZ), state, false);
        ocean.update(localX, y, localZ, state);
        surface.update(localX, y, localZ, state);
    }

    record ColumnPlan(
        int originalSurfaceY,
        int terrainTop,
        int columnTop,
        boolean steep,
        UkGeoChunkGenerator.RiverShape river,
        int vegetationClass,
        BlockState surfaceRock
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
        TRANSITION,
        DEEP,
        SOLID
    }

    record CaveMask(
        boolean usesDelegate,
        int delegateMinY,
        int transitionStartY,
        int transitionEndY,
        int deepCaveMinY,
        int deepCaveMaxY,
        int deepLavaMaxY,
        NormalNoise caveCheese,
        NormalNoise caveLayer,
        NormalNoise tunnelA,
        NormalNoise tunnelB,
        NormalNoise rarity,
        NormalNoise lavaNoise
    ) {
        boolean canSampleDelegate(int y) {
            return usesDelegate && y >= delegateMinY;
        }

        CaveMode mode(int y) {
            if (usesDelegate && y >= transitionStartY && y <= transitionEndY) {
                return CaveMode.TRANSITION;
            }
            if (usesDelegate && y > transitionEndY) {
                return CaveMode.DELEGATE;
            }
            if (y >= deepCaveMinY && y <= deepCaveMaxY) {
                return CaveMode.DEEP;
            }
            return CaveMode.SOLID;
        }

        boolean isDeepCave(int x, int y, int z) {
            if (y < deepCaveMinY || y > deepCaveMaxY) {
                return false;
            }
            double span = Math.max(1.0, deepCaveMaxY - deepCaveMinY);
            double depth = (y - deepCaveMinY) / span;
            double bedrockFade = ChunkTerrainPlanner.smoothstep((y - deepCaveMinY) / 5.0);
            double connectorBoost = 1.0 - ChunkTerrainPlanner.smoothstep((deepCaveMaxY - y) / 12.0);

            double region = rarity.getValue(x * 0.013, y * 0.018, z * 0.013);
            double chamber = caveCheese.getValue(x * 0.031, y * 0.043, z * 0.031);
            double layer = caveLayer.getValue(x * 0.018, y * 0.037, z * 0.018);
            double density = chamber * 0.74 + layer * 0.34 + region * 0.18;
            double chamberThreshold = 0.54 - connectorBoost * 0.14 + (1.0 - bedrockFade) * 0.28 - depth * 0.04;
            boolean chamberCave = region > -0.55 && density > chamberThreshold;

            double tubeA = Math.abs(tunnelA.getValue(x * 0.052, y * 0.046, z * 0.052));
            double tubeB = Math.abs(tunnelB.getValue((x + 79) * 0.052, y * 0.046, (z - 53) * 0.052));
            double tube = Math.min(tubeA, tubeB);
            double tubeThreshold = 0.105 + connectorBoost * 0.035 + depth * 0.015;
            boolean tunnelCave = region > -0.35 && tube < tubeThreshold && bedrockFade > 0.1;

            return chamberCave || tunnelCave;
        }

        boolean keepDelegateTransitionCave(int x, int y, int z) {
            if (!usesDelegate || y < delegateMinY) {
                return false;
            }
            double transitionSpan = Math.max(1.0, transitionEndY - delegateMinY);
            double delegateWeight = ChunkTerrainPlanner.smoothstep((y - delegateMinY) / transitionSpan);
            if (delegateWeight >= 0.98) {
                return true;
            }
            double chamber = caveCheese.getValue((x + 31) * 0.027, y * 0.039, (z - 17) * 0.027);
            double layer = caveLayer.getValue(x * 0.015, y * 0.031, z * 0.015);
            double tube = Math.abs(tunnelA.getValue((x - 43) * 0.049, y * 0.043, (z + 71) * 0.049));
            double connector = chamber * 0.48 + layer * 0.32 + (0.18 - tube) * 0.9;
            double threshold = 0.22 - delegateWeight * 0.32;
            return connector > threshold;
        }

        boolean isDeepLavaFloor(ChunkAccess chunk, BlockPos.MutableBlockPos cursor, int localX, int y, int localZ, int worldX, int worldZ) {
            if (y <= deepCaveMinY || y > deepLavaMaxY || !isDeepCave(worldX, y + 1, worldZ)) {
                return false;
            }
            BlockState below = chunk.getBlockState(cursor.set(localX, y - 1, localZ));
            if (below.isAir() || below.is(Blocks.LAVA) || below.is(Blocks.WATER)) {
                return false;
            }
            double span = Math.max(1.0, deepLavaMaxY - deepCaveMinY);
            double lowDepth = (deepLavaMaxY - y) / span;
            double lava = lavaNoise.getValue(worldX * 0.041, y * 0.029, worldZ * 0.041);
            double threshold = 0.56 - ChunkTerrainPlanner.smoothstep(lowDepth) * 0.16;
            return lava > threshold;
        }
    }
}
