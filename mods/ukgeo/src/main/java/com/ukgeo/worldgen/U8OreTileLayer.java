package com.ukgeo.worldgen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;

public final class U8OreTileLayer {
    private final TileManifest manifest;
    private final String oreName;
    private final String path;
    private final String extension;
    private final TileManifest.LayerStorage storage;
    private final TileGrid grid;
    private final int cellBlocks;
    private final int paddedWidth;
    private final int paddedDepth;
    private final TileCache<TileCoord, byte[]> cache;

    public U8OreTileLayer(TileManifest manifest, String oreName, String path) {
        this(manifest, oreName, path, 1, manifest.paddedWidth, manifest.paddedDepth);
    }

    public U8OreTileLayer(TileManifest manifest, String oreName, String path, int cellBlocks, int paddedWidth, int paddedDepth) {
        this.manifest = manifest;
        this.oreName = oreName;
        this.path = path;
        this.extension = manifest.u8ExtensionFor(oreName);
        this.storage = manifest.u8StorageFor(oreName, path, this.extension);
        this.grid = new TileGrid(manifest);
        this.cellBlocks = Math.max(1, cellBlocks);
        this.paddedWidth = paddedWidth;
        this.paddedDepth = paddedDepth;
        this.cache = new TileCache<>(cacheEntriesFor(oreName));
    }

    public OptionalInt sample(int x, int z) {
        return grid.locate(x, z, cellBlocks, paddedWidth, paddedDepth).flatMap(cell -> {
            try {
                byte[] tile = cache.get(cell.coord(), this::load);
                return java.util.Optional.of(Byte.toUnsignedInt(tile[cell.localZ() * manifest.tileSize + cell.localX()]));
            } catch (IOException ex) {
                UkGeoMod.LOGGER.warn("Could not read ore tile {} {}: {}", oreName, cell.coord().fileStem(), ex.getMessage());
                return java.util.Optional.empty();
            }
        }).map(OptionalInt::of).orElseGet(OptionalInt::empty);
    }

    public int sampleOrDefault(int x, int z, int defaultValue) {
        return grid.locate(x, z, cellBlocks, paddedWidth, paddedDepth).map(cell -> {
            try {
                byte[] tile = cache.get(cell.coord(), this::load);
                return Byte.toUnsignedInt(tile[cell.localZ() * manifest.tileSize + cell.localX()]);
            } catch (IOException ex) {
                UkGeoMod.LOGGER.warn("Could not read ore tile {} {}: {}", oreName, cell.coord().fileStem(), ex.getMessage());
                return defaultValue;
            }
        }).orElse(defaultValue);
    }

    private byte[] load(TileCoord coord) throws IOException {
        if (storage.usesRegions()) {
            return PackedRegionTileReader.readTile(manifest.root, storage, manifest.tileSize, coord.tileX(), coord.tileZ(), 1, 0);
        }
        Path tilePath = manifest.root.resolve(path).resolve(coord.fileStem() + extension);
        Path resolved = R16HeightTileLayer.resolveTilePath(tilePath);
        if (!Files.exists(resolved)) {
            return new byte[manifest.tileSize * manifest.tileSize];
        }
        return R16HeightTileLayer.readTileBytes(resolved, manifest.tileSize * manifest.tileSize);
    }

    public String cacheStats() {
        return oreName + ":" + cache.stats();
    }

    private static int cacheEntriesFor(String oreName) {
        int defaultEntries = switch (oreName) {
            case "vegetation", "biome_regions", "surface_geology", "rivers", "river_half_width", "river_order" -> 192;
            default -> 96;
        };
        return Math.max(1, Integer.getInteger("ukgeo.u8TileCacheEntries." + oreName, Integer.getInteger("ukgeo.u8TileCacheEntries", defaultEntries)));
    }
}
