package com.ukgeo.worldgen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
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
                short value = tile[cell.localZ() * manifest.tileSize + cell.localX()];
                return value == NODATA ? java.util.Optional.<Integer>empty() : java.util.Optional.of((int) value);
            } catch (IOException ex) {
                UkGeoMod.LOGGER.warn("Could not read height tile {}: {}", cell.coord().fileStem(), ex.getMessage());
                return java.util.Optional.empty();
            }
        }).map(OptionalInt::of).orElseGet(OptionalInt::empty);
    }

    private short[] load(TileCoord coord) throws IOException {
        Path path = manifest.root.resolve(manifest.heightPath).resolve(coord.fileStem() + ".r16.gz");
        byte[] data = readGzip(path, manifest.tileSize * manifest.tileSize * 2);
        short[] values = new short[manifest.tileSize * manifest.tileSize];
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < values.length; i++) {
            values[i] = buffer.getShort();
        }
        return values;
    }

    static byte[] readGzip(Path path, int expectedSize) throws IOException {
        byte[] data;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(path))) {
            data = in.readAllBytes();
        }
        if (data.length != expectedSize) {
            throw new IOException(path + " decompressed to " + data.length + " bytes, expected " + expectedSize);
        }
        return data;
    }

    public String cacheStats() {
        return cache.stats();
    }
}

