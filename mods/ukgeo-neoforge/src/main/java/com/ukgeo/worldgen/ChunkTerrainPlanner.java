package com.ukgeo.worldgen;

import java.io.IOException;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Precomputes per-column terrain for one chunk on a background thread, then applies blocks in bulk.
 */
final class ChunkTerrainPlanner {
    private static final int CHUNK_SIZE = 16;
    private static final int BORDER = 4;

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
        for (int localZ = 0; localZ < CHUNK_SIZE; localZ++) {
            for (int localX = 0; localX < CHUNK_SIZE; localX++) {
                int worldX = chunkMinX + localX;
                int worldZ = chunkMinZ + localZ;
                int index = localZ * CHUNK_SIZE + localX;
                int surfaceY = surfaceGrid[(localZ + BORDER) * gridSize + (localX + BORDER)];
                boolean steep = isSteep(surfaceGrid, gridSize, localX + BORDER, localZ + BORDER);
                UkGeoChunkGenerator.RiverShape river = generator.computeRiverShape(data, heightWindow, worldX, worldZ, surfaceY, minBuildY);
                int terrainTop = river.terrainSurfaceY();
                int top = Math.clamp(surfaceY, minBuildY + 1, maxBuildY);
                int columnTop = Math.clamp(
                    Math.max(Math.max(top, generator.seaLevel()), Math.max(terrainTop + 1, river.waterSurfaceY())),
                    minBuildY,
                    maxBuildY
                );
                int vegetationClass = generator.sampleVegetationClass(data, worldX, worldZ);
                BlockState surfaceRock = generator.sampleSurfaceRock(data, worldX, worldZ, terrainTop);
                columns[index] = new ColumnPlan(top, terrainTop, columnTop, steep, river, vegetationClass, surfaceRock);
            }
        }
        return new Plan(columns, generator.buildOrePlacements(data, chunk, columns), generator.seaLevel());
    }

    static void apply(Plan plan, ChunkAccess chunk) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        Heightmap ocean = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        int minBuildY = chunk.getMinBuildHeight();
        for (int localZ = 0; localZ < CHUNK_SIZE; localZ++) {
            for (int localX = 0; localX < CHUNK_SIZE; localX++) {
                ColumnPlan column = plan.columns[localZ * CHUNK_SIZE + localX];
                fillColumn(chunk, cursor, ocean, surface, localX, localZ, minBuildY, column, plan.seaLevelY());
            }
        }
        for (OrePlacement placement : plan.orePlacements) {
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

    private static void fillColumn(
        ChunkAccess chunk,
        BlockPos.MutableBlockPos cursor,
        Heightmap ocean,
        Heightmap surface,
        int localX,
        int localZ,
        int minBuildY,
        ColumnPlan column,
        int seaLevelY
    ) {
        int terrainTop = column.terrainTop();
        int columnTop = column.columnTop();
        UkGeoChunkGenerator.RiverShape river = column.river();
        BlockState surfaceRock = column.surfaceRock();
        boolean steep = column.steep();
        int originalSurfaceY = column.originalSurfaceY();
        int vegetationClass = column.vegetationClass();

        setBlock(chunk, cursor, ocean, surface, localX, minBuildY, localZ, Blocks.BEDROCK.defaultBlockState());
        int stoneTop = Math.max(minBuildY + 1, terrainTop - 12);
        for (int y = minBuildY + 1; y < stoneTop; y++) {
            setBlock(chunk, cursor, ocean, surface, localX, y, localZ, y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState());
        }
        for (int y = stoneTop; y <= columnTop; y++) {
            BlockState state = columnStateFor(y, terrainTop, minBuildY, surfaceRock, steep, river, originalSurfaceY, vegetationClass, seaLevelY);
            setBlock(chunk, cursor, ocean, surface, localX, y, localZ, state);
        }
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
            if (vegetationClass == 11) {
                return Blocks.STONE.defaultBlockState();
            }
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
}
