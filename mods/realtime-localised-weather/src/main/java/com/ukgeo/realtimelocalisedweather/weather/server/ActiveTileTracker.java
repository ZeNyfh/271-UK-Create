package com.ukgeo.realtimelocalisedweather.weather.server;

import com.ukgeo.realtimelocalisedweather.weather.WeatherTileKey;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class ActiveTileTracker {
    private ActiveTileTracker() {
    }

    public static Set<WeatherTileKey> collect(
        ResourceKey<Level> dimension,
        Collection<BlockPos> positions,
        int tileSizeBlocks,
        int activeRadius,
        int prefetchRadius
    ) {
        Set<WeatherTileKey> keys = new HashSet<>();
        int radius = Math.max(0, activeRadius) + Math.max(0, prefetchRadius);
        for (BlockPos position : positions) {
            WeatherTileKey center = WeatherTileKey.fromBlock(dimension, position.getX(), position.getZ(), tileSizeBlocks);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    keys.add(new WeatherTileKey(dimension, center.tileX() + dx, center.tileZ() + dz));
                }
            }
        }
        return keys;
    }
}
