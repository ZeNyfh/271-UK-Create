package com.ukgeo.realtimelocalisedweather;

import com.ukgeo.realtimelocalisedweather.cache.WeatherDiskCache;
import com.ukgeo.realtimelocalisedweather.cache.WeatherMemoryCache;
import com.ukgeo.realtimelocalisedweather.weather.GameplaySeverity;
import com.ukgeo.realtimelocalisedweather.weather.MeteorologicalPrecipitation;
import com.ukgeo.realtimelocalisedweather.weather.ResolvedPrecipitation;
import com.ukgeo.realtimelocalisedweather.weather.ServerWeatherSnapshot;
import com.ukgeo.realtimelocalisedweather.weather.WeatherTileKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class WeatherCacheTest {
    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));

    @Test
    void memoryCacheExpiresAndSupportsStaleFallback() {
        WeatherMemoryCache cache = new WeatherMemoryCache();
        WeatherTileKey key = new WeatherTileKey(DIMENSION, 1, 2);
        ServerWeatherSnapshot snapshot = snapshot(10L);
        Instant cachedAt = Instant.parse("2026-07-17T12:00:00Z");
        cache.put(key, snapshot, cachedAt);

        assertTrue(cache.getUsable(key, cachedAt.plus(Duration.ofMinutes(30)), Duration.ofHours(24)).isPresent());
        assertTrue(cache.get(key).orElseThrow().isStale(cachedAt.plus(Duration.ofHours(3)), Duration.ofHours(2)));
        assertTrue(cache.getUsable(key, cachedAt.plus(Duration.ofHours(25)), Duration.ofHours(24)).isEmpty());
    }

    @Test
    void diskCacheRoundTripsSnapshots() throws Exception {
        Path directory = Files.createTempDirectory("rlw-cache-test");
        WeatherDiskCache cache = new WeatherDiskCache(directory.resolve("cache.json"));
        WeatherTileKey key = new WeatherTileKey(DIMENSION, -2, 5);
        ServerWeatherSnapshot snapshot = snapshot(25L);

        cache.write(Map.of(key, snapshot));
        Map<WeatherTileKey, ServerWeatherSnapshot> restored = cache.read();

        assertEquals(1, restored.size());
        assertEquals(snapshot, restored.get(key));
    }

    private static ServerWeatherSnapshot snapshot(long revision) {
        return new ServerWeatherSnapshot(
            Instant.parse("2026-07-17T11:45:00Z"),
            55.9,
            -3.18,
            63,
            MeteorologicalPrecipitation.RAIN,
            1.4F,
            1.2F,
            0.0F,
            12.0F,
            84.0F,
            6000.0F,
            18.0F,
            240.0F,
            32.0F,
            ResolvedPrecipitation.RAIN,
            GameplaySeverity.MODERATE,
            0.2F,
            false,
            revision
        );
    }
}
