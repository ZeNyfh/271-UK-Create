package com.ukgeo.worldgen;

import java.io.IOException;
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
        int minTileX = Math.max(0, floorDiv(minBlockX - manifest.minecraftMinX, tileSize));
        int maxTileX = Math.min(manifest.tilesX() - 1, floorDiv(maxBlockX - manifest.minecraftMinX, tileSize));
        int minTileZ = Math.max(0, floorDiv(minBlockZ - manifest.minecraftMinZ, tileSize));
        int maxTileZ = Math.min(manifest.tilesZ() - 1, floorDiv(maxBlockZ - manifest.minecraftMinZ, tileSize));

        if (minTileX <= maxTileX && minTileZ <= maxTileZ) {
            for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
                int tileWorldMinZ = manifest.minecraftMinZ + tileZ * tileSize;
                int tileWorldMaxZ = tileWorldMinZ + tileSize - 1;
                int copyMinZ = Math.max(minBlockZ, tileWorldMinZ);
                int copyMaxZ = Math.min(maxBlockZ, Math.min(tileWorldMaxZ, manifest.minecraftMinZ + manifest.paddedDepth - 1));
                if (copyMinZ > copyMaxZ) {
                    continue;
                }
                for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                    int tileWorldMinX = manifest.minecraftMinX + tileX * tileSize;
                    int tileWorldMaxX = tileWorldMinX + tileSize - 1;
                    int copyMinX = Math.max(minBlockX, tileWorldMinX);
                    int copyMaxX = Math.min(maxBlockX, Math.min(tileWorldMaxX, manifest.minecraftMinX + manifest.paddedWidth - 1));
                    if (copyMinX > copyMaxX) {
                        continue;
                    }
                    short[] tile = layer.readTile(new TileCoord(tileX, tileZ));
                    int length = copyMaxX - copyMinX + 1;
                    int srcX = copyMinX - tileWorldMinX;
                    int dstX = copyMinX - minBlockX;
                    for (int worldZ = copyMinZ; worldZ <= copyMaxZ; worldZ++) {
                        int srcZ = worldZ - tileWorldMinZ;
                        int dstZ = worldZ - minBlockZ;
                        System.arraycopy(tile, srcZ * tileSize + srcX, samples, dstZ * sizeX + dstX, length);
                    }
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
        int value = R16HeightTileLayer.normalizeSample(samples[localZ * sizeX + localX]);
        return value == NODATA ? OptionalInt.empty() : OptionalInt.of(value);
    }

    int decimetresOrNodata(int worldX, int worldZ) {
        int localX = worldX - originBlockX;
        int localZ = worldZ - originBlockZ;
        if (localX < 0 || localZ < 0 || localX >= sizeX || localZ >= sizeZ) {
            return NODATA;
        }
        return R16HeightTileLayer.normalizeSample(samples[localZ * sizeX + localX]);
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
