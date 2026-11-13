package com.ukgeo.realtimelocalisedweather;

import com.ukgeo.realtimelocalisedweather.openmeteo.OpenMeteoParser;
import com.ukgeo.realtimelocalisedweather.weather.MeteorologicalPrecipitation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class OpenMeteoParsingTest {
    @Test
    void parserReadsSavedFixture() {
        var response = OpenMeteoParser.parse(TestFixtures.resource("/fixtures/openmeteo/current_ok.json"));
        assertEquals(1, response.locations().size());
        var location = response.locations().getFirst();
        assertEquals(55.9533, location.latitude(), 0.0001);
        assertEquals(63, location.weatherCode());
        assertEquals(1.4F, location.precipitation());
        assertEquals(6400.0F, location.visibility());
    }

    @Test
    void parserTreatsOpenMeteoGmtTimeWithoutOffsetAsUtc() {
        var response = OpenMeteoParser.parse(TestFixtures.resource("/fixtures/openmeteo/current_gmt_without_offset.json"));
        assertEquals(java.time.Instant.parse("2026-07-19T00:45:00Z"), response.locations().getFirst().observedAt());
    }

    @Test
    void parserRejectsMissingFields() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> OpenMeteoParser.parse(TestFixtures.resource("/fixtures/openmeteo/current_missing_field.json")));
        assertTrue(exception.getMessage().contains("weather_code"));
    }

    @Test
    void parserRejectsMalformedNumericValues() {
        assertThrows(RuntimeException.class, () -> OpenMeteoParser.parse(TestFixtures.resource("/fixtures/openmeteo/current_malformed.json")));
    }

    @Test
    void classifyPrefersSnowfallRateOverClearWeatherCode() {
        assertEquals(MeteorologicalPrecipitation.SNOW, OpenMeteoParser.classify(0, 0.8F, 0.0F, 0.0F, 0.8F));
    }

    @Test
    void classifyPrefersRainRateOverClearWeatherCode() {
        assertEquals(MeteorologicalPrecipitation.RAIN, OpenMeteoParser.classify(0, 1.2F, 1.2F, 0.0F, 0.0F));
    }

    @Test
    void classifyChoosesSnowWhenSnowfallDominatesMixedRates() {
        assertEquals(MeteorologicalPrecipitation.SNOW, OpenMeteoParser.classify(61, 1.5F, 0.4F, 0.0F, 1.0F));
    }

    @Test
    void classifyKeepsNoneWhenClearCodeAndZeroRates() {
        assertEquals(MeteorologicalPrecipitation.NONE, OpenMeteoParser.classify(0, 0.0F, 0.0F, 0.0F, 0.0F));
    }

    @Test
    void classifyKeepsRainForCodedRainWithRainRates() {
        assertEquals(MeteorologicalPrecipitation.RAIN, OpenMeteoParser.classify(63, 1.4F, 1.4F, 0.0F, 0.0F));
    }

    @Test
    void meteorologicalPrecipitationUsesClassify() {
        var response = OpenMeteoParser.parse(TestFixtures.resource("/fixtures/openmeteo/current_ok.json"));
        assertEquals(MeteorologicalPrecipitation.RAIN, response.locations().getFirst().meteorologicalPrecipitation());
    }
}
