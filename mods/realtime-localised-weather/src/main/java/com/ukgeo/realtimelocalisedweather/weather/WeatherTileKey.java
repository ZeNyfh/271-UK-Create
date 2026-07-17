package com.ukgeo.realtimelocalisedweather.weather;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record WeatherTileKey(ResourceKey<Level> dimension, int tileX, int tileZ) {
    public static WeatherTileKey fromBlock(ResourceKey<Level> dimension, int blockX, int blockZ, int tileSizeBlocks) {
        return new WeatherTileKey(dimension, Math.floorDiv(blockX, tileSizeBlocks), Math.floorDiv(blockZ, tileSizeBlocks));
    }
}
