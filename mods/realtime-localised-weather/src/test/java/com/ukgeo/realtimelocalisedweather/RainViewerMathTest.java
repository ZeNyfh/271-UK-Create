package com.ukgeo.realtimelocalisedweather;

import com.ukgeo.realtimelocalisedweather.openmeteo.OpenMeteoResponse;
import com.ukgeo.realtimelocalisedweather.rainviewer.RainViewerClient;
import com.ukgeo.realtimelocalisedweather.rainviewer.RainViewerMath;
import com.ukgeo.realtimelocalisedweather.rainviewer.RainViewerMath.BBox;
import com.ukgeo.realtimelocalisedweather.weather.MeteorologicalPrecipitation;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class RainViewerMathTest {
    private static final BBox UK = new BBox(49.5D, 61.0D, -8.5D, 2.0D);

    @Test
    void ukTileCountsPreferZ6UnderCap() {
        assertEquals(12, RainViewerMath.tileRange(UK, 6).tileCount());
        assertEquals(4, RainViewerMath.tileRange(UK, 5).tileCount());
        assertEquals(6, RainViewerMath.chooseZoom(UK, 24));
        assertEquals(5, RainViewerMath.chooseZoom(UK, 8));
    }

    @Test
    void londonPixelLandsInExpectedZ5Tile() {
        // Roughly central London
        var pixel = RainViewerMath.latLonToPixel(51.5D, -0.12D, 5);
        assertEquals(15, pixel.tileX());
        assertEquals(10, pixel.tileY());
        assertTrue(pixel.px() >= 0 && pixel.px() < RainViewerMath.TILE_SIZE);
        assertTrue(pixel.py() >= 0 && pixel.py() < RainViewerMath.TILE_SIZE);
    }

    @Test
    void intensityMappingUsesSquaredProxyAndRainCodes() {
        assertEquals(0.0F, RainViewerMath.intensityFromRgba(0, 0, 0, 0), 0.0001F);
        assertEquals(0.0F, RainViewerMath.intensityFromRgba(255, 0, 0, 4), 0.0001F);
        assertEquals(1.0F, RainViewerMath.intensityFromRgba(255, 10, 10, 255), 0.0001F);
        assertEquals(5.0F, RainViewerMath.intensityToPrecipitationMm(0.5F), 0.0001F);
        assertEquals(20.0F, RainViewerMath.intensityToPrecipitationMm(1.0F), 0.0001F);
        // intensity clamped to [0,1], so >1 still caps at intensity^2*20 = 20
        assertEquals(20.0F, RainViewerMath.intensityToPrecipitationMm(2.0F), 0.0001F);

        assertEquals(0, RainViewerMath.weatherCodeForPrecipitation(0.0F));
        assertEquals(61, RainViewerMath.weatherCodeForPrecipitation(0.5F));
        assertEquals(63, RainViewerMath.weatherCodeForPrecipitation(3.0F));
        assertEquals(65, RainViewerMath.weatherCodeForPrecipitation(10.0F));

        OpenMeteoResponse.LocationWeather weather = RainViewerMath.toLocationWeather(
            51.5D, -0.12D, Instant.parse("2026-11-18T12:00:00Z"), 0.5F
        );
        assertEquals(5.0F, weather.precipitation(), 0.0001F);
        assertEquals(5.0F, weather.rain(), 0.0001F);
        assertEquals(0.0F, weather.snowfall(), 0.0001F);
        // intensity 0.5 -> 5 mm/h -> moderate rain (63), not light (61)
        assertEquals(63, weather.weatherCode());
        assertEquals(MeteorologicalPrecipitation.RAIN, weather.meteorologicalPrecipitation());
    }

    @Test
    void parseMetaUsesLatestPastFrame() {
        String json = """
            {
              "host": "https://tilecache.rainviewer.com",
              "radar": {
                "past": [
                  {"time": 1000, "path": "/v2/radar/1000"},
                  {"time": 2000, "path": "/v2/radar/2000"}
                ]
              }
            }
            """;
        RainViewerClient.FrameMeta meta = RainViewerClient.parseMeta(json);
        assertEquals("https://tilecache.rainviewer.com", meta.host());
        assertEquals("/v2/radar/2000", meta.path());
        assertEquals(Instant.ofEpochSecond(2000), meta.observedAt());
    }
}
