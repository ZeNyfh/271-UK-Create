package com.ukgeo.worldgen;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.OptionalInt;
import java.util.logging.Logger;

/**
 * Signed 16-bit tile layer. Only {@link #NODATA} ({@code -32768}) is empty.
 * Other negative values are valid samples (Minecraft Y below zero).
 */
public final class R16IntTileLayer {
    public static final int NODATA = -32768;

    private static final Logger LOGGER = Logger.getLogger("ukgeo");

    private final TileManifest manifest;
    private final String layerName;
    private final TileManifest.LayerStorage storage;
    private final TileGrid grid;
    private final TileCache<TileCoord, short[]> cache;

    public R16IntTileLayer(TileManifest manifest, String layerName, TileManifest.LayerStorage storage) {
        this.manifest = manifest;
        this.layerName = layerName;
        this.storage = storage;
        this.grid = new TileGrid(manifest);
        this.cache = new TileCache<>(Math.max(1, Integer.getInteger("ukgeo.r16IntTileCacheEntries." + layerName, Integer.getInteger("ukgeo.r16IntTileCacheEntries", 192))));
    }

    public OptionalInt sample(int x, int z) {
        int value = sampleOrNodata(x, z);
        return value == NODATA ? OptionalInt.empty() : OptionalInt.of(value);
    }

    public int sampleOrNodata(int x, int z) {
        return grid.locate(x, z).map(cell -> {
            try {
                short[] tile = cache.get(cell.coord(), this::load);
                return decode(tile[cell.localZ() * manifest.tileSize + cell.localX()]);
            } catch (IOException ex) {
                LOGGER.warning("Could not read r16 tile " + layerName + " " + cell.coord().fileStem() + ": " + ex.getMessage());
                return NODATA;
            }
        }).orElse(NODATA);
    }

    /** Keep every value except the single nodata sentinel. */
    static int decode(short raw) {
        int value = raw;
        return value == NODATA ? NODATA : value;
    }

    private short[] load(TileCoord coord) throws IOException {
        int cells = manifest.tileSize * manifest.tileSize;
        if (!isValidTile(coord)) {
            return nodataTile(cells);
        }
        byte[] data;
        if (storage.usesRegions()) {
            data = PackedRegionTileReader.readTile(manifest.root, storage, manifest.tileSize, coord.tileX(), coord.tileZ(), 2, NODATA);
        } else {
            Path tilePath = manifest.root.resolve(storage.path()).resolve(coord.fileStem() + storage.extension());
            Path resolved = R16HeightTileLayer.resolveTilePath(tilePath);
            if (!Files.exists(resolved)) {
                return nodataTile(cells);
            }
            data = R16HeightTileLayer.readTileBytes(resolved, cells * 2);
        }
        short[] values = new short[cells];
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < values.length; i++) {
            values[i] = buffer.getShort();
        }
        return values;
    }

    private boolean isValidTile(TileCoord coord) {
        return coord.tileX() >= 0 && coord.tileZ() >= 0 && coord.tileX() < manifest.tilesX() && coord.tileZ() < manifest.tilesZ();
    }

    private static short[] nodataTile(int cells) {
        short[] values = new short[cells];
        Arrays.fill(values, (short) NODATA);
        return values;
    }
}
