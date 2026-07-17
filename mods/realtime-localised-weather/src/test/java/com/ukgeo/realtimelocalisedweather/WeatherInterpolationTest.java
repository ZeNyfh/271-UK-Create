package com.ukgeo.realtimelocalisedweather;

import com.ukgeo.realtimelocalisedweather.weather.GameplaySeverity;
import com.ukgeo.realtimelocalisedweather.weather.MeteorologicalPrecipitation;
import com.ukgeo.realtimelocalisedweather.weather.ResolvedPrecipitation;
import com.ukgeo.realtimelocalisedweather.weather.ServerWeatherSnapshot;
import com.ukgeo.realtimelocalisedweather.weather.WeatherMath;
import com.ukgeo.realtimelocalisedweather.weather.WeatherTileKey;
import com.ukgeo.realtimelocalisedweather.weather.client.ClientWeatherTile;
import com.ukgeo.realtimelocalisedweather.weather.client.WeatherInterpolator;
import java.time.Instant;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class WeatherInterpolationTest {
    @Test
    void bilinearInterpolationBlendsFourNeighbours() {
        float value = WeatherMath.bilinear(0.0F, 10.0F, 20.0F, 30.0F, 0.5F, 0.5F);
        assertEquals(15.0F, value, 0.0001F);
    }

    @Test
    void temporalInterpolationMovesBetweenSnapshots() {
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));
        ClientWeatherTile tile = new ClientWeatherTile(new WeatherTileKey(dimension, 0, 0), snapshot(0.0F), snapshot(4.0F), 1_000L, 42L);
        float rate = WeatherInterpolator.interpolateRate(tile, 13_000L);
        assertTrue(rate > 1.8F && rate < 2.2F);
    }

    @Test
    void hysteresisPreventsRapidRainSnowFlips() {
        assertEquals(ResolvedPrecipitation.SNOW, WeatherMath.hysteresis(ResolvedPrecipitation.SNOW, ResolvedPrecipitation.RAIN, 0.4F, 1.0F));
        assertEquals(ResolvedPrecipitation.RAIN, WeatherMath.hysteresis(ResolvedPrecipitation.RAIN, ResolvedPrecipitation.SNOW, 0.8F, 1.0F));
        assertEquals(ResolvedPrecipitation.SNOW, WeatherMath.hysteresis(ResolvedPrecipitation.RAIN, ResolvedPrecipitation.SNOW, -2.0F, 1.0F));
    }

    private static ServerWeatherSnapshot snapshot(float precipitationRate) {
        return new ServerWeatherSnapshot(
            Instant.parse("2026-07-17T11:45:00Z"),
            55.9,
            -3.18,
            63,
            MeteorologicalPrecipitation.RAIN,
            precipitationRate,
            precipitationRate,
            0.0F,
            5.0F,
            80.0F,
            90.0F,
            70.0F,
            60.0F,
            40.0F,
            7000.0F,
            12.0F,
            180.0F,
            20.0F,
            ResolvedPrecipitation.RAIN,
            GameplaySeverity.LIGHT,
            0.0F,
            false,
            1L
        );
    }
}
