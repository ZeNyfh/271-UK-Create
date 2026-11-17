package com.ukgeo.realtimelocalisedweather.openmeteo;

import com.ukgeo.worldgen.geo.UkGeoReference;
import com.ukgeo.worldgen.geo.Wgs84Coordinate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * National multipoint Open-Meteo lattice over the UKGeo WGS84 bbox.
 * One refresh covers the map; weather tiles sample locally (bilinear continuous fields, nearest weather_code).
 */
public final class NationalWeatherGrid {
    public static final int MAX_POINTS = 96;

    private final int columns;
    private final int rows;
    private final double[] latitudes;
    private final double[] longitudes;
    private OpenMeteoResponse.LocationWeather[] points;
    private Instant observedAt = Instant.EPOCH;

    private NationalWeatherGrid(int columns, int rows, double[] latitudes, double[] longitudes) {
        this.columns = columns;
        this.rows = rows;
        this.latitudes = latitudes;
        this.longitudes = longitudes;
    }

    public static NationalWeatherGrid create(UkGeoReference reference, int requestedColumns, int requestedRows) {
        int[] dims = clampDimensions(requestedColumns, requestedRows);
        int columns = dims[0];
        int rows = dims[1];
        double[] latitudes = new double[columns * rows];
        double[] longitudes = new double[columns * rows];
        double minX = reference.minecraftMinX();
        double maxX = reference.minecraftMaxX();
        double minZ = reference.minecraftMinZ();
        double maxZ = reference.minecraftMaxZ();
        int index = 0;
        for (int row = 0; row < rows; row++) {
            double mz = rows == 1 ? (minZ + maxZ) * 0.5D : minZ + row * (maxZ - minZ) / (rows - 1);
            for (int col = 0; col < columns; col++) {
                double mx = columns == 1 ? (minX + maxX) * 0.5D : minX + col * (maxX - minX) / (columns - 1);
                Optional<Wgs84Coordinate> wgs84 = reference.minecraftToWgs84(mx, mz);
                if (wgs84.isEmpty()) {
                    // Edge clamp: nudge inward half a block if corner conversion fails.
                    wgs84 = reference.minecraftToWgs84(
                        Math.min(maxX, Math.max(minX, mx)),
                        Math.min(maxZ, Math.max(minZ, mz))
                    );
                }
                Wgs84Coordinate coordinate = wgs84.orElseThrow(() -> new IllegalStateException("National weather grid point outside UKGeo reference"));
                latitudes[index] = coordinate.latitude();
                longitudes[index] = coordinate.longitude();
                index++;
            }
        }
        return new NationalWeatherGrid(columns, rows, latitudes, longitudes);
    }

    /** Synthetic grid for unit tests (no network / no UKGeo reference). */
    public static NationalWeatherGrid fromPoints(
        int columns,
        int rows,
        double[] latitudes,
        double[] longitudes,
        OpenMeteoResponse.LocationWeather[] points,
        Instant observedAt
    ) {
        if (latitudes.length != columns * rows || longitudes.length != columns * rows || points.length != columns * rows) {
            throw new IllegalArgumentException("lat/lon/points length must equal columns*rows");
        }
        NationalWeatherGrid grid = new NationalWeatherGrid(columns, rows, latitudes.clone(), longitudes.clone());
        grid.points = points.clone();
        grid.observedAt = observedAt;
        return grid;
    }

    public static int[] clampDimensions(int requestedColumns, int requestedRows) {
        int columns = Math.max(2, requestedColumns);
        int rows = Math.max(2, requestedRows);
        int total = columns * rows;
        if (total > MAX_POINTS) {
            double scale = Math.sqrt((double) MAX_POINTS / total);
            columns = Math.max(2, (int) Math.floor(columns * scale));
            rows = Math.max(2, Math.min(MAX_POINTS / columns, (int) Math.floor(rows * scale)));
            while (columns * rows > MAX_POINTS && columns > 2) {
                columns--;
            }
            while (columns * rows > MAX_POINTS && rows > 2) {
                rows--;
            }
        }
        return new int[] {columns, rows};
    }

    public int columns() {
        return columns;
    }

    public int rows() {
        return rows;
    }

    public int pointCount() {
        return columns * rows;
    }

    public Instant observedAt() {
        return observedAt;
    }

    public boolean hasData() {
        return points != null;
    }

    public boolean isFresh(Duration maxAge) {
        return hasData() && !Instant.now().isAfter(observedAt.plus(maxAge));
    }

    public boolean matchesDimensions(int requestedColumns, int requestedRows) {
        int[] dims = clampDimensions(requestedColumns, requestedRows);
        return this.columns == dims[0] && this.rows == dims[1];
    }

    public CompletableFuture<Boolean> refresh(OpenMeteoClient client) {
        List<OpenMeteoResponse.LocationRequest> requests = new ArrayList<>(pointCount());
        for (int i = 0; i < pointCount(); i++) {
            requests.add(new OpenMeteoResponse.LocationRequest("g" + i, latitudes[i], longitudes[i]));
        }
        return client.fetchCurrent(requests).thenApply(locations -> {
            if (locations == null || locations.size() < pointCount()) {
                throw new IllegalStateException(
                    "National weather grid expected " + pointCount() + " locations, got "
                        + (locations == null ? 0 : locations.size())
                );
            }
            OpenMeteoResponse.LocationWeather[] next = new OpenMeteoResponse.LocationWeather[pointCount()];
            Instant newest = Instant.EPOCH;
            for (int i = 0; i < pointCount(); i++) {
                OpenMeteoResponse.LocationWeather location = locations.get(i);
                next[i] = location;
                if (location.observedAt().isAfter(newest)) {
                    newest = location.observedAt();
                }
            }
            this.points = next;
            this.observedAt = newest.equals(Instant.EPOCH) ? Instant.now() : newest;
            return Boolean.TRUE;
        });
    }

    public Optional<OpenMeteoResponse.LocationWeather> sample(double latitude, double longitude) {
        if (!hasData() || columns < 2 || rows < 2) {
            return Optional.empty();
        }
        // Map query into fractional grid coords using corner lat/lon extents (row-major, Z southward).
        double lat00 = latitudes[0];
        double lon00 = longitudes[0];
        double lat10 = latitudes[columns - 1];
        double lon10 = longitudes[columns - 1];
        double lat01 = latitudes[(rows - 1) * columns];
        double lon01 = longitudes[(rows - 1) * columns];

        double colDenomLon = lon10 - lon00;
        double colDenomLat = lat10 - lat00;
        double rowDenomLon = lon01 - lon00;
        double rowDenomLat = lat01 - lat00;
        double colF;
        double rowF;
        if (Math.abs(colDenomLon) >= Math.abs(colDenomLat)) {
            colF = colDenomLon == 0.0D ? 0.0D : (longitude - lon00) / colDenomLon * (columns - 1);
        } else {
            colF = colDenomLat == 0.0D ? 0.0D : (latitude - lat00) / colDenomLat * (columns - 1);
        }
        if (Math.abs(rowDenomLat) >= Math.abs(rowDenomLon)) {
            rowF = rowDenomLat == 0.0D ? 0.0D : (latitude - lat00) / rowDenomLat * (rows - 1);
        } else {
            rowF = rowDenomLon == 0.0D ? 0.0D : (longitude - lon00) / rowDenomLon * (rows - 1);
        }
        colF = clamp(colF, 0.0D, columns - 1.0D);
        rowF = clamp(rowF, 0.0D, rows - 1.0D);

        int col0 = (int) Math.floor(colF);
        int row0 = (int) Math.floor(rowF);
        int col1 = Math.min(columns - 1, col0 + 1);
        int row1 = Math.min(rows - 1, row0 + 1);
        float tx = (float) (colF - col0);
        float ty = (float) (rowF - row0);

        OpenMeteoResponse.LocationWeather v00 = points[index(row0, col0)];
        OpenMeteoResponse.LocationWeather v10 = points[index(row0, col1)];
        OpenMeteoResponse.LocationWeather v01 = points[index(row1, col0)];
        OpenMeteoResponse.LocationWeather v11 = points[index(row1, col1)];

        OpenMeteoResponse.LocationWeather nearest = nearestCorner(v00, v10, v01, v11, tx, ty);
        Instant observed = nearest.observedAt();
        return Optional.of(new OpenMeteoResponse.LocationWeather(
            latitude,
            longitude,
            observed,
            nearest.weatherCode(),
            bilinear(v00.precipitation(), v10.precipitation(), v01.precipitation(), v11.precipitation(), tx, ty),
            bilinear(v00.rain(), v10.rain(), v01.rain(), v11.rain(), tx, ty),
            bilinear(v00.showers(), v10.showers(), v01.showers(), v11.showers(), tx, ty),
            bilinear(v00.snowfall(), v10.snowfall(), v01.snowfall(), v11.snowfall(), tx, ty),
            bilinear(v00.visibility(), v10.visibility(), v01.visibility(), v11.visibility(), tx, ty),
            bilinear(v00.temperature(), v10.temperature(), v01.temperature(), v11.temperature(), tx, ty),
            bilinear(v00.humidity(), v10.humidity(), v01.humidity(), v11.humidity(), tx, ty),
            bilinear(v00.windSpeed(), v10.windSpeed(), v01.windSpeed(), v11.windSpeed(), tx, ty),
            bilinear(v00.windDirection(), v10.windDirection(), v01.windDirection(), v11.windDirection(), tx, ty),
            bilinear(v00.windGusts(), v10.windGusts(), v01.windGusts(), v11.windGusts(), tx, ty)
        ));
    }

    private int index(int row, int col) {
        return row * columns + col;
    }

    private static float bilinear(float v00, float v10, float v01, float v11, float tx, float ty) {
        float a = v00 + (v10 - v00) * tx;
        float b = v01 + (v11 - v01) * tx;
        return a + (b - a) * ty;
    }

    private static OpenMeteoResponse.LocationWeather nearestCorner(
        OpenMeteoResponse.LocationWeather v00,
        OpenMeteoResponse.LocationWeather v10,
        OpenMeteoResponse.LocationWeather v01,
        OpenMeteoResponse.LocationWeather v11,
        float tx,
        float ty
    ) {
        float w00 = (1.0F - tx) * (1.0F - ty);
        float w10 = tx * (1.0F - ty);
        float w01 = (1.0F - tx) * ty;
        float w11 = tx * ty;
        float best = w00;
        OpenMeteoResponse.LocationWeather nearest = v00;
        if (w10 > best) {
            best = w10;
            nearest = v10;
        }
        if (w01 > best) {
            best = w01;
            nearest = v01;
        }
        if (w11 > best) {
            nearest = v11;
        }
        return nearest;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
