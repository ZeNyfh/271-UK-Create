package com.ukgeo.realtimelocalisedweather.rainviewer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ukgeo.realtimelocalisedweather.RealtimeLocalisedWeatherMod;
import com.ukgeo.realtimelocalisedweather.config.ServerWeatherConfig;
import com.ukgeo.realtimelocalisedweather.openmeteo.OpenMeteoResponse;
import com.ukgeo.realtimelocalisedweather.rainviewer.RainViewerMath.BBox;
import com.ukgeo.realtimelocalisedweather.rainviewer.RainViewerMath.Pixel;
import com.ukgeo.realtimelocalisedweather.rainviewer.RainViewerMath.SampledIntensities;
import com.ukgeo.realtimelocalisedweather.rainviewer.RainViewerMath.TileRange;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import javax.imageio.ImageIO;

/**
 * Server-side RainViewer radar client: caches weather-maps.json + XYZ PNG tiles (z=5/6/7),
 * dual-fetches snow=0 (intensity) + snow=1 (snow mask), samples rain/snow mm/h at WGS84 points.
 */
public final class RainViewerClient {
    public static final String DEFAULT_META_URL = "https://api.rainviewer.com/public/weather-maps.json";
    public static final String ATTRIBUTION_URL = "https://www.rainviewer.com/";
    private static final int COLOR = 2;
    private static final int SMOOTH = 1;
    private static final int SNOW_OFF = 0;
    private static final int SNOW_ON = 1;

    private final HttpClient httpClient;
    private final Map<String, CachedTile> tileCache = new ConcurrentHashMap<>();
    private volatile FrameMeta frame;

    public RainViewerClient(Executor executor) {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(executor)
            .build();
    }

    public CompletableFuture<Void> ensureFrame() {
        Duration ttl = Duration.ofSeconds(Math.max(60, ServerWeatherConfig.safeInt(ServerWeatherConfig.RAINVIEWER_META_TTL_SECONDS, 180)));
        FrameMeta current = frame;
        if (current != null && Instant.now().isBefore(current.fetchedAt().plus(ttl))) {
            return CompletableFuture.completedFuture(null);
        }
        String metaUrl = ServerWeatherConfig.safeString(ServerWeatherConfig.RAINVIEWER_META_URL, DEFAULT_META_URL);
        HttpRequest request = HttpRequest.newBuilder(URI.create(metaUrl))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", "RealtimeLocalisedWeather/" + RealtimeLocalisedWeatherMod.MOD_VERSION)
            .GET()
            .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("RainViewer meta HTTP " + response.statusCode());
                }
                FrameMeta next = parseMeta(response.body());
                FrameMeta previous = frame;
                frame = next;
                if (previous == null || !previous.path().equals(next.path())) {
                    tileCache.clear();
                }
            });
    }

    /**
     * Ensure tiles covering bbox are cached at a zoom that fits maxTiles (prefer 7, else 6/5).
     * maxTiles is per snow-mode path; fetches both snow=0 and snow=1 (2× HTTP).
     */
    public CompletableFuture<Void> ensureTiles(BBox bbox, int maxTiles) {
        return ensureFrame().thenCompose(ignored -> {
            FrameMeta meta = requireFrame();
            int zoom = RainViewerMath.chooseZoom(bbox, maxTiles);
            TileRange range = RainViewerMath.tileRange(bbox, zoom);
            if (range.tileCount() > Math.max(1, maxTiles)) {
                throw new IllegalStateException(
                    "RainViewer tile count " + range.tileCount() + " exceeds cap " + maxTiles + " at z=" + zoom
                );
            }
            List<CompletableFuture<Void>> fetches = new ArrayList<>();
            for (int ty = range.minY(); ty <= range.maxY(); ty++) {
                for (int tx = range.minX(); tx <= range.maxX(); tx++) {
                    for (int snow : new int[] {SNOW_OFF, SNOW_ON}) {
                        String key = tileKey(meta.path(), zoom, tx, ty, snow);
                        if (tileCache.containsKey(key)) {
                            continue;
                        }
                        fetches.add(fetchTile(meta, zoom, tx, ty, snow));
                    }
                }
            }
            if (fetches.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.allOf(fetches.toArray(CompletableFuture[]::new));
        });
    }

    public Optional<SampledIntensities> sampleIntensities(double latitude, double longitude) {
        FrameMeta meta = frame;
        if (meta == null) {
            return Optional.empty();
        }
        for (int zoom : new int[] {7, 6, 5}) {
            Pixel pixel = RainViewerMath.latLonToPixel(latitude, longitude, zoom);
            CachedTile intensityTile = tileCache.get(tileKey(meta.path(), zoom, pixel.tileX(), pixel.tileY(), SNOW_OFF));
            CachedTile maskTile = tileCache.get(tileKey(meta.path(), zoom, pixel.tileX(), pixel.tileY(), SNOW_ON));
            if (intensityTile == null || maskTile == null) {
                continue;
            }
            float intensity = intensityTile.intensity(pixel.px(), pixel.py());
            boolean snow = maskTile.isSnow(pixel.px(), pixel.py());
            if (snow) {
                return Optional.of(new SampledIntensities(0.0F, intensity));
            }
            return Optional.of(new SampledIntensities(intensity, 0.0F));
        }
        return Optional.empty();
    }

    /** Dominant channel intensity (rain or snow); prefer {@link #sampleIntensities}. */
    public Optional<Float> sampleIntensity(double latitude, double longitude) {
        return sampleIntensities(latitude, longitude)
            .map(sample -> Math.max(sample.rainIntensity(), sample.snowIntensity()));
    }

    public Optional<OpenMeteoResponse.LocationWeather> sampleLocation(double latitude, double longitude) {
        FrameMeta meta = frame;
        if (meta == null) {
            return Optional.empty();
        }
        return sampleIntensities(latitude, longitude)
            .map(sample -> RainViewerMath.toLocationWeather(
                latitude,
                longitude,
                meta.observedAt(),
                sample.rainIntensity(),
                sample.snowIntensity()
            ));
    }

    public boolean hasFrame() {
        return frame != null;
    }

    public Instant observedAt() {
        FrameMeta meta = frame;
        return meta == null ? Instant.EPOCH : meta.observedAt();
    }

    public int cachedTileCount() {
        return tileCache.size();
    }

    /** Test hook: install a decoded tile without HTTP (snowMode 0 or 1). */
    void putTileForTest(String path, int zoom, int x, int y, int snowMode, BufferedImage image, Instant observedAt) {
        frame = new FrameMeta("https://tilecache.rainviewer.com", path, observedAt, Instant.now());
        tileCache.put(tileKey(path, zoom, x, y, snowMode), new CachedTile(image));
    }

    private CompletableFuture<Void> fetchTile(FrameMeta meta, int zoom, int x, int y, int snow) {
        String url = meta.host() + meta.path() + "/" + RainViewerMath.TILE_SIZE + "/" + zoom + "/" + x + "/" + y
            + "/" + COLOR + "/" + SMOOTH + "_" + snow + ".png";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "RealtimeLocalisedWeather/" + RealtimeLocalisedWeatherMod.MOD_VERSION)
            .GET()
            .build();
        String key = tileKey(meta.path(), zoom, x, y, snow);
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
            .thenAccept(response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("RainViewer tile HTTP " + response.statusCode() + ": " + url);
                }
                try {
                    BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.body()));
                    if (image == null) {
                        throw new IllegalStateException("RainViewer tile decode failed: " + url);
                    }
                    tileCache.put(key, new CachedTile(image));
                } catch (IOException exception) {
                    throw new IllegalStateException("RainViewer tile decode failed: " + url, exception);
                }
            });
    }

    private FrameMeta requireFrame() {
        FrameMeta meta = frame;
        if (meta == null) {
            throw new IllegalStateException("RainViewer weather-maps.json not loaded");
        }
        return meta;
    }

    public static FrameMeta parseMeta(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String host = root.has("host") ? root.get("host").getAsString().replaceAll("/+$", "") : "";
        JsonObject radar = root.has("radar") && root.get("radar").isJsonObject() ? root.getAsJsonObject("radar") : null;
        JsonArray past = radar != null && radar.has("past") && radar.get("past").isJsonArray()
            ? radar.getAsJsonArray("past")
            : null;
        if (host.isBlank() || past == null || past.isEmpty()) {
            throw new IllegalStateException("RainViewer weather-maps.json missing host/path");
        }
        JsonObject frameObj = past.get(past.size() - 1).getAsJsonObject();
        String path = frameObj.has("path") ? frameObj.get("path").getAsString() : "";
        if (path.isBlank()) {
            throw new IllegalStateException("RainViewer weather-maps.json missing host/path");
        }
        long epoch = frameObj.has("time") ? frameObj.get("time").getAsLong() : Instant.now().getEpochSecond();
        Instant observedAt = Instant.ofEpochSecond(epoch);
        return new FrameMeta(host, path, observedAt, Instant.now());
    }

    private static String tileKey(String path, int zoom, int x, int y, int snow) {
        return path + "|" + zoom + "|" + x + "|" + y + "|s" + snow;
    }

    public record FrameMeta(String host, String path, Instant observedAt, Instant fetchedAt) {
    }

    private static final class CachedTile {
        private final BufferedImage image;

        private CachedTile(BufferedImage image) {
            this.image = image;
        }

        private int argb(int px, int py) {
            int x = Math.max(0, Math.min(image.getWidth() - 1, px));
            int y = Math.max(0, Math.min(image.getHeight() - 1, py));
            return image.getRGB(x, y);
        }

        private float intensity(int px, int py) {
            return RainViewerMath.intensityFromArgb(argb(px, py));
        }

        private boolean isSnow(int px, int py) {
            return RainViewerMath.isSnowTintArgb(argb(px, py));
        }
    }
}
