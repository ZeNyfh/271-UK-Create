package com.ukgeo.realtimelocalisedweather.rainviewer;

import com.ukgeo.realtimelocalisedweather.openmeteo.OpenMeteoResponse;
import java.time.Instant;

/**
 * Pure RainViewer WebMercator / intensity helpers (no network).
 * Met Office–calibrated scheme-2 colour proxy for mm/h (not gauge rainfall).
 * snow=1 tiles retint snow-classified echoes; use {@link #isSnowTint} to split channels.
 */
public final class RainViewerMath {
    public static final int TILE_SIZE = 256;
    public static final int MAX_Z = 7;
    public static final float PRECIPITATION_MAX_MM = 40.0F;
    public static final float INTENSITY_ALPHA_THRESHOLD = 8.0F;

    private static final float[] INTENSITY_STOPS = {
        0.0F, 0.15F, 0.25F, 0.35F, 0.45F, 0.55F, 0.65F, 0.80F, 1.0F
    };
    private static final float[] MM_STOPS = {
        0.0F, 0.40F, 0.75F, 1.50F, 3.00F, 6.00F, 12.0F, 24.0F, 40.0F
    };

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

    /** Rain / snow intensity pair (0–1 scheme-2), one channel usually 0. */
    public record SampledIntensities(float rainIntensity, float snowIntensity) {
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

    /** Prefer z=7 for denser UK coverage when under maxTiles; else z=6 / z=5. */
    public static int chooseZoom(BBox bbox, int maxTiles) {
        int cap = Math.max(1, maxTiles);
        for (int zoom : new int[] {7, 6, 5}) {
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

    /**
     * RainViewer snow=1 retints snow-classified echoes pale cyan/white
     * (e.g. RGB ~200–206,255,255). Avoid marking normal rain yellows/blues.
     */
    public static boolean isSnowTint(int r, int g, int b, int a) {
        return a >= 8
            && b >= 220
            && g >= 220
            && r >= 160
            && r < 240
            && Math.abs(g - b) <= 20;
    }

    public static boolean isSnowTintArgb(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        return isSnowTint(r, g, b, a);
    }

    /**
     * 0–1 intensity from RainViewer scheme-2 RGBA.
     * Warm yellow/orange/red uses green falloff; cool blue/cyan uses max(g,b).
     */
    public static float intensityFromRgba(int r, int g, int b, int a) {
        if (a < INTENSITY_ALPHA_THRESHOLD) {
            return 0.0F;
        }
        if (r >= 200 && b <= 80) {
            float t = 1.0F - clamp01(g / 255.0F);
            return 0.62F + 0.38F * t;
        }
        return 0.62F * Math.max(g, b) / 255.0F;
    }

    public static float intensityFromArgb(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        return intensityFromRgba(r, g, b, a);
    }

    /** Met Office–aligned piecewise mm/h from scheme-2 intensity, capped. */
    public static float intensityToPrecipitationMm(float intensity) {
        float x = clamp01(intensity);
        for (int i = 1; i < INTENSITY_STOPS.length; i++) {
            if (x <= INTENSITY_STOPS[i]) {
                float x0 = INTENSITY_STOPS[i - 1];
                float x1 = INTENSITY_STOPS[i];
                float y0 = MM_STOPS[i - 1];
                float y1 = MM_STOPS[i];
                float t = (x - x0) / Math.max(1.0e-6F, x1 - x0);
                return Math.min(PRECIPITATION_MAX_MM, y0 + t * (y1 - y0));
            }
        }
        return PRECIPITATION_MAX_MM;
    }

    /** WMO rain bands when precip > 0; clear otherwise. */
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

    /** WMO snow bands 71/73/75 by rate (cm/h or liquid-equivalent mm/h proxy). */
    public static int weatherCodeForSnowfall(float snowfallCm) {
        if (snowfallCm <= 0.05F) {
            return 0;
        }
        if (snowfallCm < 2.0F) {
            return 71;
        }
        if (snowfallCm < 8.0F) {
            return 73;
        }
        return 75;
    }

    /**
     * Build LocationWeather from dual rain/snow intensities (0–1).
     * Snowfall field uses liquid-equivalent mm/h as cm/h snow-depth proxy (Open-Meteo scale).
     */
    public static OpenMeteoResponse.LocationWeather toLocationWeather(
        double latitude,
        double longitude,
        Instant observedAt,
        float rainIntensity,
        float snowIntensity
    ) {
        float rainMm = intensityToPrecipitationMm(rainIntensity);
        float snowMm = intensityToPrecipitationMm(snowIntensity);
        float snowfall = snowMm;
        float precip = rainMm + snowMm;
        int weatherCode;
        if (snowfall > rainMm && snowfall > 0.05F) {
            weatherCode = weatherCodeForSnowfall(snowfall);
        } else {
            weatherCode = weatherCodeForPrecipitation(rainMm);
        }
        return new OpenMeteoResponse.LocationWeather(
            latitude,
            longitude,
            observedAt,
            weatherCode,
            precip,
            rainMm,
            0.0F,
            snowfall,
            10000.0F,
            10.0F,
            70.0F,
            0.0F,
            0.0F,
            0.0F
        );
    }

    /** Rain-only convenience (snow intensity 0). */
    public static OpenMeteoResponse.LocationWeather toLocationWeather(
        double latitude,
        double longitude,
        Instant observedAt,
        float intensity
    ) {
        return toLocationWeather(latitude, longitude, observedAt, intensity, 0.0F);
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
