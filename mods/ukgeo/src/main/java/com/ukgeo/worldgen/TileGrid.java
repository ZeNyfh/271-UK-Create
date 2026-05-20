package com.ukgeo.worldgen;

import java.util.Optional;

public final class TileGrid {
    private final TileManifest manifest;

    public TileGrid(TileManifest manifest) {
        this.manifest = manifest;
    }

    public Optional<Cell> locate(int minecraftX, int minecraftZ) {
        return locate(minecraftX, minecraftZ, 1, manifest.paddedWidth, manifest.paddedDepth);
    }

    public Optional<Cell> locate(int minecraftX, int minecraftZ, int cellBlocks, int paddedWidth, int paddedDepth) {
        int blocks = Math.max(1, cellBlocks);
        int dataX = Math.floorDiv(minecraftX - manifest.minecraftMinX, blocks);
        int dataZ = Math.floorDiv(minecraftZ - manifest.minecraftMinZ, blocks);
        int dataWidth = Math.floorDiv(paddedWidth + blocks - 1, blocks);
        int dataDepth = Math.floorDiv(paddedDepth + blocks - 1, blocks);
        if (dataX < 0 || dataZ < 0 || dataX >= dataWidth || dataZ >= dataDepth) {
            return Optional.empty();
        }
        int tileX = Math.floorDiv(dataX, manifest.tileSize);
        int tileZ = Math.floorDiv(dataZ, manifest.tileSize);
        int localX = Math.floorMod(dataX, manifest.tileSize);
        int localZ = Math.floorMod(dataZ, manifest.tileSize);
        return Optional.of(new Cell(new TileCoord(tileX, tileZ), localX, localZ));
    }

    public record Cell(TileCoord coord, int localX, int localZ) {
    }
}

