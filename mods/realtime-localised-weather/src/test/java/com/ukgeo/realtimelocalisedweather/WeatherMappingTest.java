package com.ukgeo.realtimelocalisedweather;

import com.ukgeo.realtimelocalisedweather.weather.GameplaySeverity;
import com.ukgeo.realtimelocalisedweather.weather.MeteorologicalPrecipitation;
import com.ukgeo.realtimelocalisedweather.weather.ResolvedPrecipitation;
import com.ukgeo.realtimelocalisedweather.weather.ServerWeatherSnapshot;
import com.ukgeo.realtimelocalisedweather.weather.WeatherSeverityMapper;
import com.ukgeo.realtimelocalisedweather.weather.WeatherTileKey;
import com.ukgeo.realtimelocalisedweather.openmeteo.OpenMeteoParser;
import java.time.Instant;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class WeatherMappingTest {
    @Test
    void weatherCodeMappingCoversWmoBands() {
        assertEquals(MeteorologicalPrecipitation.NONE, OpenMeteoParser.mapWeatherCode(0));
        assertEquals(MeteorologicalPrecipitation.DRIZZLE, OpenMeteoParser.mapWeatherCode(53));
        assertEquals(MeteorologicalPrecipitation.FREEZING_RAIN, OpenMeteoParser.mapWeatherCode(67));
        assertEquals(MeteorologicalPrecipitation.SNOW, OpenMeteoParser.mapWeatherCode(75));
        assertEquals(MeteorologicalPrecipitation.SHOWERS, OpenMeteoParser.mapWeatherCode(82));
        assertEquals(MeteorologicalPrecipitation.HAIL, OpenMeteoParser.mapWeatherCode(96));
    }

    @Test
    void precipitationRateMapsToStableSeverityBands() {
        assertEquals(GameplaySeverity.TRACE, WeatherSeverityMapper.fromRates(0.01F, 0.0F, 0.0F));
        assertEquals(GameplaySeverity.LIGHT, WeatherSeverityMapper.fromRates(0.2F, 0.0F, 0.0F));
        assertEquals(GameplaySeverity.MODERATE, WeatherSeverityMapper.fromRates(1.2F, 0.0F, 0.0F));
        assertEquals(GameplaySeverity.HEAVY, WeatherSeverityMapper.fromRates(4.0F, 0.0F, 0.0F));
        assertEquals(GameplaySeverity.EXTREME, WeatherSeverityMapper.fromRates(12.0F, 0.0F, 0.0F));
    }

    @Test
    void positiveRateCountsAsPrecipitationEvenWithClearWeatherCode() {
        ServerWeatherSnapshot snapshot = new ServerWeatherSnapshot(
            Instant.parse("2026-07-18T18:00:00Z"),
            55.0,
            -3.0,
            0,
            MeteorologicalPrecipitation.NONE,
            0.1F,
            0.1F,
            0.0F,
            12.0F,
            80.0F,
            100.0F,
            100.0F,
            100.0F,
            100.0F,
            10000.0F,
            10.0F,
            180.0F,
            20.0F,
            ResolvedPrecipitation.NONE,
            GameplaySeverity.TRACE,
            0.0F,
            false,
            1L
        );

        assertTrue(snapshot.hasPrecipitation());
    }

    @Test
    void negativeTileCoordinatesUseFloorDivision() {
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));
        WeatherTileKey key = WeatherTileKey.fromBlock(dimension, -1, -257, 256);
        assertEquals(-1, key.tileX());
        assertEquals(-2, key.tileZ());
    }
}
