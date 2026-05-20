package com.ukgeo.worldgen;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

/**
 * In-memory height samples for a block window. Avoids per-sample tile loading during chunk generation.
 */
final class HeightTileWindow {
    static final short NODATA = R16HeightTileLayer.NODATA;

    private final int originBlockX;
    private final int originBlockZ;
    private final int sizeX;
    private final int sizeZ;
    private final short[] samples;

    private HeightTileWindow(int originBlockX, int originBlockZ, int sizeX, int sizeZ, short[] samples) {
        this.originBlockX = originBlockX;
        this.originBlockZ = originBlockZ;
        this.sizeX = sizeX;
        this.sizeZ = sizeZ;
        this.samples = samples;
    }

    static HeightTileWindow forChunk(R16HeightTileLayer layer, TileManifest manifest, int chunkMinX, int chunkMinZ, int margin) throws IOException {
        int minBlockX = chunkMinX - margin;
        int minBlockZ = chunkMinZ - margin;
        int maxBlockX = chunkMinX + 15 + margin;
        int maxBlockZ = chunkMinZ + 15 + margin;
        int sizeX = maxBlockX - minBlockX + 1;
        int sizeZ = maxBlockZ - minBlockZ + 1;
        short[] samples = new short[sizeX * sizeZ];
        java.util.Arrays.fill(samples, NODATA);

        int tileSize = manifest.tileSize;
        int minTileX = floorDiv(minBlockX - manifest.minecraftMinX, tileSize);
        int maxTileX = floorDiv(maxBlockX - manifest.minecraftMinX, tileSize);
        int minTileZ = floorDiv(minBlockZ - manifest.minecraftMinZ, tileSize);
        int maxTileZ = floorDiv(maxBlockZ - manifest.minecraftMinZ, tileSize);

        Map<TileCoord, short[]> tiles = new HashMap<>();
        for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
            for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                tiles.put(new TileCoord(tileX, tileZ), layer.readTile(new TileCoord(tileX, tileZ)));
            }
        }

        for (int worldZ = minBlockZ; worldZ <= maxBlockZ; worldZ++) {
            int dataZ = worldZ - manifest.minecraftMinZ;
            int tileZ = floorDiv(dataZ, tileSize);
            int localZ = floorMod(dataZ, tileSize);
            for (int worldX = minBlockX; worldX <= maxBlockX; worldX++) {
                int dataX = worldX - manifest.minecraftMinX;
                int tileX = floorDiv(dataX, tileSize);
                int localX = floorMod(dataX, tileSize);
                short[] tile = tiles.get(new TileCoord(tileX, tileZ));
                if (tile != null) {
                    samples[(worldZ - minBlockZ) * sizeX + (worldX - minBlockX)] = tile[localZ * tileSize + localX];
                }
            }
        }
        return new HeightTileWindow(minBlockX, minBlockZ, sizeX, sizeZ, samples);
    }

    OptionalInt decimetres(int worldX, int worldZ) {
        int localX = worldX - originBlockX;
        int localZ = worldZ - originBlockZ;
        if (localX < 0 || localZ < 0 || localX >= sizeX || localZ >= sizeZ) {
            return OptionalInt.empty();
        }
        short value = samples[localZ * sizeX + localX];
        return value == NODATA ? OptionalInt.empty() : OptionalInt.of(value);
    }

    private static int floorDiv(int a, int b) {
        int result = a / b;
        if ((a ^ b) < 0 && result * b != a) {
            result--;
        }
        return result;
    }

    private static int floorMod(int a, int b) {
        return a - floorDiv(a, b) * b;
    }
}
