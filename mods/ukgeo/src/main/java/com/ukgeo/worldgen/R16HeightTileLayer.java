package com.ukgeo.worldgen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.OptionalInt;
import java.util.zip.GZIPInputStream;

public final class R16HeightTileLayer {
    public static final short NODATA = (short) -32768;

    private final TileManifest manifest;
    private final TileGrid grid;
    private final TileCache<TileCoord, short[]> cache = new TileCache<>(96);

    public R16HeightTileLayer(TileManifest manifest) {
        this.manifest = manifest;
        this.grid = new TileGrid(manifest);
    }

    public OptionalInt sampleDecimetres(int x, int z) {
        return grid.locate(x, z).flatMap(cell -> {
            try {
                short[] tile = cache.get(cell.coord(), this::load);
                int value = normalizeSample(tile[cell.localZ() * manifest.tileSize + cell.localX()]);
                return value == NODATA ? java.util.Optional.<Integer>empty() : java.util.Optional.of(value);
            } catch (IOException ex) {
                UkGeoMod.LOGGER.warn("Could not read height tile {}: {}", cell.coord().fileStem(), ex.getMessage());
                return java.util.Optional.empty();
            }
        }).map(OptionalInt::of).orElseGet(OptionalInt::empty);
    }

    public int sampleDecimetresOrNodata(int x, int z) {
        return grid.locate(x, z).map(cell -> {
            try {
                short[] tile = cache.get(cell.coord(), this::load);
                return normalizeSample(tile[cell.localZ() * manifest.tileSize + cell.localX()]);
            } catch (IOException ex) {
                UkGeoMod.LOGGER.warn("Could not read height tile {}: {}", cell.coord().fileStem(), ex.getMessage());
                return (int) NODATA;
            }
        }).orElse((int) NODATA);
    }

    /**
     * Treat negative source heights as ocean/no-data. The source raster can contain
     * below-sea bathymetry, but UKGeo should not generate underwater terrain from it.
     */
    static int normalizeSample(short value) {
        return value < 0 ? (int) NODATA : (int) value;
    }

    short[] readTile(TileCoord coord) throws IOException {
        if (!isValidTile(coord)) {
            return nodataTile();
        }
        return cache.get(coord, this::load);
    }

    private short[] load(TileCoord coord) throws IOException {
        if (!isValidTile(coord)) {
            return nodataTile();
        }
        byte[] data;
        if (manifest.heightStorage.usesRegions()) {
            data = PackedRegionTileReader.readTile(manifest.root, manifest.heightStorage, manifest.tileSize, coord.tileX(), coord.tileZ(), 2, NODATA);
        } else {
            Path path = manifest.root.resolve(manifest.heightPath).resolve(coord.fileStem() + manifest.heightExtension);
            Path resolved = resolveTilePath(path);
            if (!Files.exists(resolved)) {
                return nodataTile();
            }
            data = readTileBytes(resolved, manifest.tileSize * manifest.tileSize * 2);
        }
        short[] values = new short[manifest.tileSize * manifest.tileSize];
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < values.length; i++) {
            values[i] = buffer.getShort();
        }
        return values;
    }

    private boolean isValidTile(TileCoord coord) {
        return coord.tileX() >= 0 && coord.tileZ() >= 0 && coord.tileX() < manifest.tilesX() && coord.tileZ() < manifest.tilesZ();
    }

    private short[] nodataTile() {
        short[] values = new short[manifest.tileSize * manifest.tileSize];
        Arrays.fill(values, NODATA);
        return values;
    }

    static byte[] readTileBytes(Path path, int expectedSize) throws IOException {
        Path resolved = resolveTilePath(path);
        byte[] data;
        if (resolved.getFileName().toString().endsWith(".gz")) {
            try (InputStream in = new GZIPInputStream(Files.newInputStream(resolved))) {
                data = in.readAllBytes();
            }
        } else {
            data = Files.readAllBytes(resolved);
        }
        if (data.length != expectedSize) {
            throw new IOException(resolved + " raw tile size was " + data.length + " bytes, expected " + expectedSize);
        }
        return data;
    }

    static Path resolveTilePath(Path path) {
        if (Files.exists(path)) {
            return path;
        }
        String fileName = path.getFileName().toString();
        Path parent = path.getParent();
        if (fileName.endsWith(".gz")) {
            Path raw = parent == null ? Path.of(fileName.substring(0, fileName.length() - 3)) : parent.resolve(fileName.substring(0, fileName.length() - 3));
            if (Files.exists(raw)) {
                return raw;
            }
        } else {
            Path gzip = parent == null ? Path.of(fileName + ".gz") : parent.resolve(fileName + ".gz");
            if (Files.exists(gzip)) {
                return gzip;
            }
        }
        return path;
    }

    /** Backwards-compatible name for old call sites. Reads raw or gzip based on the file name. */
    static byte[] readGzip(Path path, int expectedSize) throws IOException {
        return readTileBytes(path, expectedSize);
    }

    public String cacheStats() {
        return cache.stats();
    }
}
