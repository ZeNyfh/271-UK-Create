package com.ukgeo.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeightTileWindowTest {
    @TempDir
    Path root;

    @Test
    void structureQueriesWithoutWindowReadDatasetAcrossTileBoundary() throws Exception {
        TileManifest manifest = fixture();
        var layer = new R16HeightTileLayer(manifest);
        assertEquals(120, HeightTileWindow.sampleDecimetresOrNodata(null, layer, -1, -8));
        assertEquals(240, HeightTileWindow.sampleDecimetresOrNodata(null, layer, 0, -8));
        assertEquals(HeightTileWindow.NODATA, HeightTileWindow.sampleDecimetresOrNodata(null, layer, 40, -8));
        assertEquals(HeightTileWindow.NODATA, HeightTileWindow.sampleDecimetresOrNodata(null, null, -1, -8));
    }

    @Test
    void chunkQueriesKeepWindowSamplesAndMissingData() throws Exception {
        TileManifest manifest = fixture();
        var layer = new R16HeightTileLayer(manifest);
        var window = HeightTileWindow.forChunk(layer, manifest, -16, -16, 0);
        // A window query must not need a fallback layer or spill into an unloaded window area.
        assertEquals(120, HeightTileWindow.sampleDecimetresOrNodata(window, null, -1, -8));
        assertEquals(HeightTileWindow.NODATA, HeightTileWindow.sampleDecimetresOrNodata(window, layer, 0, -8));
        assertEquals(HeightTileWindow.NODATA, HeightTileWindow.sampleDecimetresOrNodata(window, layer, -16, -16));
    }

    private TileManifest fixture() throws Exception {
        Files.writeString(root.resolve("manifest.json"), """
            {
              "format": "uk-raster-tiles-v1",
              "tile_size": 16,
              "world": {
                "width": 32, "depth": 16, "padded_width": 32, "padded_depth": 16,
                "minecraft_min_x": -16, "minecraft_min_z": -16,
                "minecraft_max_x": 15, "minecraft_max_z": -1
              },
              "height": {"sea_level_y": 64, "path": "height", "extension": ".r16"}
            }
            """);
        Files.createDirectory(root.resolve("height"));
        for (int tile = 0; tile < 2; tile++) {
            ByteBuffer buffer = ByteBuffer.allocate(16 * 16 * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 16 * 16; i++) {
                buffer.putShort(i == 0 ? HeightTileWindow.NODATA : (short) (120 * (tile + 1)));
            }
            Files.write(root.resolve("height/%03d_000.r16".formatted(tile)), buffer.array());
        }
        return TileManifest.load(root);
    }
}
