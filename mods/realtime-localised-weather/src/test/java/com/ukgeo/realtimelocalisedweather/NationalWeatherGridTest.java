package com.ukgeo.realtimelocalisedweather;

import com.ukgeo.realtimelocalisedweather.openmeteo.NationalWeatherGrid;
import com.ukgeo.realtimelocalisedweather.openmeteo.OpenMeteoResponse;
import com.ukgeo.worldgen.geo.UkGeoReference;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class NationalWeatherGridTest {
    private static final Instant OBSERVED = Instant.parse("2026-07-17T12:00:00Z");

    @Test
    void bilinearSampleInterpolatesContinuousFieldsAndNearestWeatherCode() {
        // 2x2 regular lat/lon lattice; precipitation corners 0,10 / 20,30 → centre = 15
        double[] lats = {54.0, 54.0, 56.0, 56.0};
        double[] lons = {-6.0, -2.0, -6.0, -2.0};
        OpenMeteoResponse.LocationWeather[] points = {
            weather(54.0, -6.0, 61, 0.0F, 0.0F),
            weather(54.0, -2.0, 63, 10.0F, 1.0F),
            weather(56.0, -6.0, 71, 20.0F, 2.0F),
            weather(56.0, -2.0, 73, 30.0F, 3.0F)
        };
        NationalWeatherGrid grid = NationalWeatherGrid.fromPoints(2, 2, lats, lons, points, OBSERVED);

        var centre = grid.sample(55.0, -4.0).orElseThrow();
        assertEquals(15.0F, centre.precipitation(), 0.001F);
        assertEquals(1.5F, centre.snowfall(), 0.001F);
        assertEquals(55.0, centre.latitude(), 0.0001);
        assertEquals(-4.0, centre.longitude(), 0.0001);
        // Equal weights → first corner wins (v00 weather code 61)
        assertEquals(61, centre.weatherCode());

        var nearTopRight = grid.sample(54.1, -2.1).orElseThrow();
        assertEquals(63, nearTopRight.weatherCode());
        assertTrue(nearTopRight.precipitation() > 8.0F);
    }

    @Test
    void clampDimensionsRespectsMaxPoints() {
        int[] dims = NationalWeatherGrid.clampDimensions(20, 20);
        assertTrue(dims[0] * dims[1] <= NationalWeatherGrid.MAX_POINTS);
        assertTrue(dims[0] >= 2);
        assertTrue(dims[1] >= 2);
    }

    @Test
    void createBuildsLatticeOverUkGeoReference() {
        UkGeoReference reference = new UkGeoReference(
            "EPSG:27700",
            -26050,
            -36925,
            7411,
            13074,
            -220000.0,
            0.0,
            650000.0,
            1300000.0,
            33462,
            50000
        );
        NationalWeatherGrid grid = NationalWeatherGrid.create(reference, 8, 10);
        assertEquals(8, grid.columns());
        assertEquals(10, grid.rows());
        assertEquals(80, grid.pointCount());
        assertFalse(grid.hasData());
        assertFalse(grid.isFresh(Duration.ofMinutes(15)));
    }

    @Test
    void isFreshUsesObservedAt() {
        double[] lats = {54.0, 54.0, 56.0, 56.0};
        double[] lons = {-6.0, -2.0, -6.0, -2.0};
        OpenMeteoResponse.LocationWeather[] points = {
            weather(54.0, -6.0, 0, 0.0F, 0.0F),
            weather(54.0, -2.0, 0, 0.0F, 0.0F),
            weather(56.0, -6.0, 0, 0.0F, 0.0F),
            weather(56.0, -2.0, 0, 0.0F, 0.0F)
        };
        NationalWeatherGrid grid = NationalWeatherGrid.fromPoints(2, 2, lats, lons, points, Instant.now().minus(Duration.ofMinutes(5)));
        assertTrue(grid.isFresh(Duration.ofMinutes(15)));
        assertFalse(grid.isFresh(Duration.ofMinutes(1)));
    }

    private static OpenMeteoResponse.LocationWeather weather(
        double lat,
        double lon,
        int weatherCode,
        float precipitation,
        float snowfall
    ) {
        return new OpenMeteoResponse.LocationWeather(
            lat,
            lon,
            OBSERVED,
            weatherCode,
            precipitation,
            precipitation,
            0.0F,
            snowfall,
            10000.0F,
            10.0F,
            70.0F,
            5.0F,
            180.0F,
            8.0F
        );
    }
}
