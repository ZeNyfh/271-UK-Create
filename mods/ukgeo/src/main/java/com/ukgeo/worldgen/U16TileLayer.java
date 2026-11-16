package com.ukgeo.worldgen;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.OptionalInt;
import java.util.logging.Logger;

/** Unsigned 16-bit tile layer. Missing tiles and out-of-range samples are 0. */
public final class U16TileLayer {
    private static final Logger LOGGER = Logger.getLogger("ukgeo");

    private final TileManifest manifest;
    private final String layerName;
    private final TileManifest.LayerStorage storage;
    private final TileGrid grid;
    private final TileCache<TileCoord, short[]> cache;

    public U16TileLayer(TileManifest manifest, String layerName, TileManifest.LayerStorage storage) {
        this.manifest = manifest;
        this.layerName = layerName;
        this.storage = storage;
        this.grid = new TileGrid(manifest);
        this.cache = new TileCache<>(Math.max(1, Integer.getInteger("ukgeo.u16TileCacheEntries." + layerName, Integer.getInteger("ukgeo.u16TileCacheEntries", 192))));
    }

    public OptionalInt sample(int x, int z) {
        return grid.locate(x, z).map(cell -> {
            try {
                short[] tile = cache.get(cell.coord(), this::load);
                return OptionalInt.of(Short.toUnsignedInt(tile[cell.localZ() * manifest.tileSize + cell.localX()]));
            } catch (IOException ex) {
                LOGGER.warning("Could not read u16 tile " + layerName + " " + cell.coord().fileStem() + ": " + ex.getMessage());
                return OptionalInt.empty();
            }
        }).orElseGet(OptionalInt::empty);
    }

    public int sampleOrDefault(int x, int z, int defaultValue) {
        OptionalInt value = sample(x, z);
        return value.isPresent() ? value.getAsInt() : defaultValue;
    }

    private short[] load(TileCoord coord) throws IOException {
        int cells = manifest.tileSize * manifest.tileSize;
        if (!isValidTile(coord)) {
            return missingTile(cells);
        }
        byte[] data;
        if (storage.usesRegions()) {
            data = PackedRegionTileReader.readTile(manifest.root, storage, manifest.tileSize, coord.tileX(), coord.tileZ(), 2, storage.missingTile());
        } else {
            Path tilePath = manifest.root.resolve(storage.path()).resolve(coord.fileStem() + storage.extension());
            Path resolved = R16HeightTileLayer.resolveTilePath(tilePath);
            if (!Files.exists(resolved)) {
                return missingTile(cells);
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

    private short[] missingTile(int cells) {
        short[] values = new short[cells];
        int missing = storage.missingTile();
        if (missing != 0) {
            Arrays.fill(values, (short) (missing & 0xFFFF));
        }
        return values;
    }
}
