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
    void ukTileCountsPreferZ7UnderCap() {
        assertEquals(40, RainViewerMath.tileRange(UK, 7).tileCount());
        assertEquals(12, RainViewerMath.tileRange(UK, 6).tileCount());
        assertEquals(4, RainViewerMath.tileRange(UK, 5).tileCount());
        assertEquals(7, RainViewerMath.chooseZoom(UK, 48));
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
    void intensityMappingUsesMetOfficeCalibrationAndRainCodes() {
        assertEquals(0.0F, RainViewerMath.intensityFromRgba(0, 0, 0, 0), 0.0001F);
        assertEquals(0.0F, RainViewerMath.intensityFromRgba(255, 0, 0, 4), 0.0001F);

        // Mid blue drizzle: cool branch → 0.62 * 170/255 ≈ 0.413 → ~2.2–2.6 mm/h
        float blueIntensity = RainViewerMath.intensityFromRgba(0, 119, 170, 255);
        assertEquals(0.62F * 170.0F / 255.0F, blueIntensity, 0.001F);
        float blueMm = RainViewerMath.intensityToPrecipitationMm(blueIntensity);
        assertTrue(blueMm >= 2.2F && blueMm <= 2.6F, "blue drizzle mm/h was " + blueMm);

        // Full yellow: warm branch → ~8–16 mm/h band (around 12)
        float yellowIntensity = RainViewerMath.intensityFromRgba(255, 224, 0, 255);
        float yellowMm = RainViewerMath.intensityToPrecipitationMm(yellowIntensity);
        assertTrue(yellowMm >= 8.0F && yellowMm <= 16.0F, "yellow mm/h was " + yellowMm);
        assertEquals(12.0F, yellowMm, 2.0F);

        // Red-orange: high mm/h near/above 24–40
        float redIntensity = RainViewerMath.intensityFromRgba(242, 54, 0, 255);
        float redMm = RainViewerMath.intensityToPrecipitationMm(redIntensity);
        assertTrue(redMm >= 24.0F && redMm <= 40.0F, "red-orange mm/h was " + redMm);

        assertEquals(40.0F, RainViewerMath.intensityToPrecipitationMm(1.0F), 0.0001F);
        assertEquals(40.0F, RainViewerMath.intensityToPrecipitationMm(2.0F), 0.0001F);

        assertEquals(0, RainViewerMath.weatherCodeForPrecipitation(0.0F));
        assertEquals(61, RainViewerMath.weatherCodeForPrecipitation(0.5F));
        assertEquals(63, RainViewerMath.weatherCodeForPrecipitation(3.0F));
        assertEquals(65, RainViewerMath.weatherCodeForPrecipitation(10.0F));

        // intensity 0.5 → between 3 and 6 mm/h → code 63
        OpenMeteoResponse.LocationWeather weather = RainViewerMath.toLocationWeather(
            51.5D, -0.12D, Instant.parse("2026-11-18T12:00:00Z"), 0.5F
        );
        assertTrue(weather.precipitation() > 3.0F && weather.precipitation() < 6.0F);
        assertEquals(weather.precipitation(), weather.rain(), 0.0001F);
        assertEquals(0.0F, weather.snowfall(), 0.0001F);
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
