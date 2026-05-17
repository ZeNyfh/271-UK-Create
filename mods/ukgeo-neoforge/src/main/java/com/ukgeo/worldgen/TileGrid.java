package com.ukgeo.worldgen;

import java.util.Optional;

public final class TileGrid {
    private final TileManifest manifest;

    public TileGrid(TileManifest manifest) {
        this.manifest = manifest;
    }

    public Optional<Cell> locate(int minecraftX, int minecraftZ) {
        int dataX = minecraftX - manifest.minecraftMinX;
        int dataZ = minecraftZ - manifest.minecraftMinZ;
        if (dataX < 0 || dataZ < 0 || dataX >= manifest.paddedWidth || dataZ >= manifest.paddedDepth) {
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

