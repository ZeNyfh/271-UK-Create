package com.ukgeo.realtimelocalisedweather;

import com.ukgeo.realtimelocalisedweather.weather.GameplaySeverity;
import com.ukgeo.realtimelocalisedweather.weather.ResolvedPrecipitation;
import com.ukgeo.realtimelocalisedweather.weather.WeatherTileKey;
import com.ukgeo.realtimelocalisedweather.weather.server.ActiveTileTracker;
import com.ukgeo.realtimelocalisedweather.weather.server.WeatherOverrideManager;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActiveTilesAndOverridesTest {
    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));

    @Test
    void activeTilesMergeAcrossPlayers() {
        Set<WeatherTileKey> tiles = ActiveTileTracker.collect(
            DIMENSION,
            List.of(new BlockPos(0, 64, 0), new BlockPos(300, 64, 0)),
            256,
            1,
            0
        );
        assertTrue(tiles.contains(new WeatherTileKey(DIMENSION, 0, 0)));
        assertTrue(tiles.contains(new WeatherTileKey(DIMENSION, 1, 0)));
        assertTrue(tiles.size() < 18);
    }

    @Test
    void manualOverrideExpiryReturnsControlToLiveWeather() throws Exception {
        WeatherOverrideManager manager = new WeatherOverrideManager();
        WeatherTileKey key = new WeatherTileKey(DIMENSION, 0, 0);
        manager.setGlobal(ResolvedPrecipitation.RAIN, GameplaySeverity.MODERATE, 10L);
        assertTrue(manager.lookup(key, System.currentTimeMillis()).isPresent());
        Thread.sleep(20L);
        manager.clearExpired(System.currentTimeMillis());
        assertTrue(manager.lookup(key, System.currentTimeMillis()).isEmpty());
    }
}
