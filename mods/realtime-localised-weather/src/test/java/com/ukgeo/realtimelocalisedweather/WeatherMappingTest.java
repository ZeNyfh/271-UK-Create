package com.ukgeo.realtimelocalisedweather;

import com.ukgeo.realtimelocalisedweather.weather.GameplaySeverity;
import com.ukgeo.realtimelocalisedweather.weather.MeteorologicalPrecipitation;
import com.ukgeo.realtimelocalisedweather.weather.WeatherSeverityMapper;
import com.ukgeo.realtimelocalisedweather.weather.WeatherTileKey;
import com.ukgeo.realtimelocalisedweather.openmeteo.OpenMeteoParser;
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
    void negativeTileCoordinatesUseFloorDivision() {
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));
        WeatherTileKey key = WeatherTileKey.fromBlock(dimension, -1, -257, 256);
        assertEquals(-1, key.tileX());
        assertEquals(-2, key.tileZ());
    }
}
