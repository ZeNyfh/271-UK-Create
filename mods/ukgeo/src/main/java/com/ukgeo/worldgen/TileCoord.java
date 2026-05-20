package com.ukgeo.worldgen;

public record TileCoord(int tileX, int tileZ) {
    public String fileStem() {
        return "%03d_%03d".formatted(tileX, tileZ);
    }
}

