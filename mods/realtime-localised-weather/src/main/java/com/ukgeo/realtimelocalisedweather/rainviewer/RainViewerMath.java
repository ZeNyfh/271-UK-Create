package com.ukgeo.realtimelocalisedweather.rainviewer;

import com.ukgeo.realtimelocalisedweather.openmeteo.OpenMeteoResponse;
import java.time.Instant;

/**
 * Pure RainViewer WebMercator / intensity helpers (no network).
 * Reflectivity proxy mm/h = intensity^2 * 20 (capped), not gauge rainfall.
 */
public final class RainViewerMath {
    public static final int TILE_SIZE = 256;
    public static final int MAX_Z = 7;
    public static final float PRECIPITATION_MAX_MM = 30.0F;
    public static final float INTENSITY_ALPHA_THRESHOLD = 8.0F;

    private RainViewerMath() {
    }

    public record TileXY(int x, int y) {
    }

    public record TileRange(int minX, int maxX, int minY, int maxY) {
        public int tileCount() {
            return (maxX - minX + 1) * (maxY - minY + 1);
        }
    }

    public record BBox(double minLat, double maxLat, double minLon, double maxLon) {
    }

    public record Pixel(int tileX, int tileY, int px, int py) {
    }

    public static TileXY wgs84ToTileXY(double lon, double lat, int zoom) {
        int n = 1 << zoom;
        int x = (int) Math.floor((lon + 180.0D) / 360.0D * n);
        double latRad = Math.toRadians(lat);
        int y = (int) Math.floor((1.0D - Math.log(Math.tan(latRad) + 1.0D / Math.cos(latRad)) / Math.PI) / 2.0D * n);
        return new TileXY(clamp(x, 0, n - 1), clamp(y, 0, n - 1));
    }

    public static TileRange tileRange(BBox bbox, int zoom) {
        TileXY nw = wgs84ToTileXY(bbox.minLon(), bbox.maxLat(), zoom);
        TileXY se = wgs84ToTileXY(bbox.maxLon(), bbox.minLat(), zoom);
        int maxIndex = (1 << zoom) - 1;
        return new TileRange(
            clamp(Math.min(nw.x(), se.x()), 0, maxIndex),
            clamp(Math.max(nw.x(), se.x()), 0, maxIndex),
            clamp(Math.min(nw.y(), se.y()), 0, maxIndex),
            clamp(Math.max(nw.y(), se.y()), 0, maxIndex)
        );
    }

    /** Prefer z=6 for denser UK coverage when under maxTiles; else z=5 (~4 tiles). */
    public static int chooseZoom(BBox bbox, int maxTiles) {
        int cap = Math.max(1, maxTiles);
        for (int zoom : new int[] {6, 5, 4}) {
            if (zoom > MAX_Z) {
                continue;
            }
            if (tileRange(bbox, zoom).tileCount() <= cap) {
                return zoom;
            }
        }
        return 5;
    }

    public static Pixel latLonToPixel(double lat, double lon, int zoom) {
        int n = 1 << zoom;
        double x = (lon + 180.0D) / 360.0D * n * TILE_SIZE;
        double latRad = Math.toRadians(lat);
        double y = (1.0D - Math.log(Math.tan(latRad) + 1.0D / Math.cos(latRad)) / Math.PI) / 2.0D * n * TILE_SIZE;
        int tileX = (int) Math.floor(x / TILE_SIZE);
        int tileY = (int) Math.floor(y / TILE_SIZE);
        int px = (int) Math.round(x - tileX * (double) TILE_SIZE);
        int py = (int) Math.round(y - tileY * (double) TILE_SIZE);
        px = clamp(px, 0, TILE_SIZE - 1);
        py = clamp(py, 0, TILE_SIZE - 1);
        return new Pixel(tileX, tileY, px, py);
    }

    /** 0–1 intensity from RGBA; transparent / near-transparent → 0. */
    public static float intensityFromRgba(int r, int g, int b, int a) {
        if (a < INTENSITY_ALPHA_THRESHOLD) {
            return 0.0F;
        }
        return Math.max(r, Math.max(g, b)) / 255.0F;
    }

    public static float intensityFromArgb(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        return intensityFromRgba(r, g, b, a);
    }

    /** Reflectivity proxy mm/h: intensity^2 * 20, capped. */
    public static float intensityToPrecipitationMm(float intensity) {
        float clamped = Math.max(0.0F, Math.min(1.0F, intensity));
        return Math.min(PRECIPITATION_MAX_MM, clamped * clamped * 20.0F);
    }

    /** WMO rain bands when precip > 0; clear otherwise. Snow always 0 from radar. */
    public static int weatherCodeForPrecipitation(float precipitationMm) {
        if (precipitationMm <= 0.05F) {
            return 0;
        }
        if (precipitationMm < 2.0F) {
            return 61;
        }
        if (precipitationMm < 8.0F) {
            return 63;
        }
        return 65;
    }

    public static OpenMeteoResponse.LocationWeather toLocationWeather(
        double latitude,
        double longitude,
        Instant observedAt,
        float intensity
    ) {
        float precip = intensityToPrecipitationMm(intensity);
        int weatherCode = weatherCodeForPrecipitation(precip);
        return new OpenMeteoResponse.LocationWeather(
            latitude,
            longitude,
            observedAt,
            weatherCode,
            precip,
            precip,
            0.0F,
            0.0F,
            10000.0F,
            10.0F,
            70.0F,
            0.0F,
            0.0F,
            0.0F
        );
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
