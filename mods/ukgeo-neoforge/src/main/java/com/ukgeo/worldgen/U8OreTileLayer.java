package com.ukgeo.worldgen;

import java.io.IOException;
import java.nio.file.Path;
import java.util.OptionalInt;

public final class U8OreTileLayer {
    private final TileManifest manifest;
    private final String oreName;
    private final String path;
    private final TileGrid grid;
    private final TileCache<TileCoord, byte[]> cache = new TileCache<>(96);

    public U8OreTileLayer(TileManifest manifest, String oreName, String path) {
        this.manifest = manifest;
        this.oreName = oreName;
        this.path = path;
        this.grid = new TileGrid(manifest);
    }

    public OptionalInt sample(int x, int z) {
        return grid.locate(x, z).flatMap(cell -> {
            try {
                byte[] tile = cache.get(cell.coord(), this::load);
                return java.util.Optional.of(Byte.toUnsignedInt(tile[cell.localZ() * manifest.tileSize + cell.localX()]));
            } catch (IOException ex) {
                UkGeoMod.LOGGER.warn("Could not read ore tile {} {}: {}", oreName, cell.coord().fileStem(), ex.getMessage());
                return java.util.Optional.empty();
            }
        }).map(OptionalInt::of).orElseGet(OptionalInt::empty);
    }

    private byte[] load(TileCoord coord) throws IOException {
        Path tilePath = manifest.root.resolve(path).resolve(coord.fileStem() + ".u8.gz");
        return R16HeightTileLayer.readGzip(tilePath, manifest.tileSize * manifest.tileSize);
    }

    public String cacheStats() {
        return oreName + ":" + cache.stats();
    }
}

