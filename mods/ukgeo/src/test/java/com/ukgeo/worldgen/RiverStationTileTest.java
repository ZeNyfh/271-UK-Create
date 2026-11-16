package com.ukgeo.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiverStationTileTest {
    @Test
    void missingSchemaDisablesCarveAndListsGaps() throws IOException {
        TileManifest bare = load(temp("bare"), manifest(16, 16, """
            "rivers": {"path": "water/rivers", "extension": ".u8"}
            """));
        List<String> problems = bare.riverStationProblems();
        assertFalse(bare.riverStationsEnabled());
        String message = RiverStationColumn.disabledMessage(problems);
        for (String name : List.of("station_schema_version", "reach_id_path", "station_id_path", "water_y_path", "flow_azimuth_path")) {
            assertTrue(message.contains(name), message);
        }

        TileManifest wrongVersion = load(temp("v2"), manifest(16, 16, """
            "rivers": {
              "path": "water/rivers",
              "station_schema_version": 2,
              "reach_id_path": "water/river_reach_id",
              "station_id_path": "water/river_station_id",
              "water_y_path": "water/river_water_y",
              "flow_azimuth_path": "water/river_flow_azimuth"
            }
            """));
        assertEquals(List.of("station_schema_version=2 (required 1)"), wrongVersion.riverStationProblems());
        assertFalse(wrongVersion.riverStationsEnabled());

        TileManifest missingWater = load(temp("nowater"), manifest(16, 16, """
            "rivers": {
              "path": "water/rivers",
              "station_schema_version": 1,
              "reach_id_path": "water/river_reach_id",
              "station_id_path": "water/river_station_id",
              "flow_azimuth_path": "water/river_flow_azimuth"
            }
            """));
        assertEquals(List.of("water_y_path"), missingWater.riverStationProblems());
    }

    @Test
    void looseTilesKeepNegativesAndShareSeamStation() throws IOException {
        Path root = temp("loose");
        Files.writeString(root.resolve("manifest.json"), manifest(16, 32, """
            "rivers": {
              "path": "water/rivers",
              "extension": ".u8",
              "station_schema_version": 1,
              "reach_id_path": "water/river_reach_id",
              "station_id_path": "water/river_station_id",
              "water_y_path": "water/river_water_y",
              "flow_azimuth_path": "water/river_flow_azimuth"
            }
            """));
        TileManifest manifest = TileManifest.load(root);
        assertTrue(manifest.riverStationsEnabled());
        assertEquals(".u32", manifest.riverReachIdExtension);
        assertEquals(".u32rg", manifest.riverReachIdStorage.regionExtension());
        assertEquals(0, manifest.riverReachIdStorage.missingTile());
        assertEquals(".u16rg", manifest.riverStationIdStorage.regionExtension());
        assertTrue(manifest.u32Storages.containsKey("river_reach_id"));
        assertFalse(manifest.u16Storages.containsKey("river_reach_id"));
        assertTrue(manifest.u16Storages.containsKey("river_station_id"));
        assertEquals(".r16rg", manifest.riverWaterYStorage.regionExtension());
        assertEquals(-32768, manifest.riverWaterYStorage.missingTile());
        assertEquals(".u8rg", manifest.riverFlowAzimuthStorage.regionExtension());

        int cells = 16 * 16;
        for (int tileX = 0; tileX < 2; tileX++) {
            for (int tileZ = 0; tileZ < 2; tileZ++) {
                ByteBuffer reach = ints(cells);
                ByteBuffer station = shorts(cells);
                ByteBuffer water = shorts(cells);
                for (int i = 0; i < cells; i++) {
                    water.putShort((short) R16IntTileLayer.NODATA);
                }
                water.flip();
                ByteBuffer azimuth = ByteBuffer.allocate(cells);
                if (tileX == 0 && tileZ == 0) {
                    putU32(reach, 0, 0, 1);
                    putU16(station, 0, 0, 2);
                    putI16(water, 0, 0, -40);
                    azimuth.put(0, (byte) 10);
                    putU32(reach, 1, 0, 0);
                    putI16(water, 1, 0, R16IntTileLayer.NODATA);
                    putU32(reach, 2, 0, 65536);
                    putU16(station, 2, 0, 9);
                    putI16(water, 2, 0, -32767);
                    azimuth.put(3, (byte) 200);
                    putU32(reach, 15, 15, 345610);
                    putU16(station, 15, 15, 40000);
                    putI16(water, 15, 15, 90);
                    azimuth.put(15 * 16 + 15, (byte) 40);
                }
                if (tileX == 1 && tileZ == 0) {
                    putU32(reach, 0, 15, 345610);
                    putU16(station, 0, 15, 40000);
                    putI16(water, 0, 15, 90);
                    azimuth.put(15 * 16 + 0, (byte) 40);
                }
                if (tileX == 0 && tileZ == 1) {
                    putU32(reach, 15, 0, 345610);
                    putU16(station, 15, 0, 40000);
                    putI16(water, 15, 0, 90);
                    azimuth.put(0 * 16 + 15, (byte) 40);
                }
                if (tileX == 1 && tileZ == 1) {
                    putU32(reach, 0, 0, 345610);
                    putU16(station, 0, 0, 40000);
                    putI16(water, 0, 0, 90);
                    azimuth.put(0, (byte) 40);
                }
                String stem = "%03d_%03d".formatted(tileX, tileZ);
                writeBytes(root.resolve("water/river_reach_id").resolve(stem + ".u32"), reach);
                writeBytes(root.resolve("water/river_station_id").resolve(stem + ".u16"), station);
                writeBytes(root.resolve("water/river_water_y").resolve(stem + ".r16"), water);
                Path azimuthPath = root.resolve("water/river_flow_azimuth").resolve(stem + ".u8");
                Files.createDirectories(azimuthPath.getParent());
                Files.write(azimuthPath, azimuth.array());
            }
        }

        assertEquals(".u8", manifest.u8ExtensionFor("river_flow_azimuth"));
        assertTrue(manifest.u8Storages.containsKey("river_flow_azimuth"));

        U32TileLayer reach = new U32TileLayer(manifest, "river_reach_id", manifest.riverReachIdStorage);
        U16TileLayer station = new U16TileLayer(manifest, "river_station_id", manifest.riverStationIdStorage);
        R16IntTileLayer water = new R16IntTileLayer(manifest, "river_water_y", manifest.riverWaterYStorage);

        assertEquals(-40, water.sampleOrNodata(0, 0));
        assertEquals(-40, water.sample(0, 0).getAsInt());
        assertTrue(water.sample(1, 0).isEmpty());
        assertEquals(R16IntTileLayer.NODATA, water.sampleOrNodata(1, 0));
        assertEquals(-32767, water.sampleOrNodata(2, 0));
        assertEquals(0, reach.sampleOrDefault(1, 0, 99));
        assertEquals(65536, reach.sampleOrDefault(2, 0, 0));
        assertEquals(200, Byte.toUnsignedInt(Files.readAllBytes(root.resolve("water/river_flow_azimuth/000_000.u8"))[3]));
        assertEquals(0, reach.sampleOrDefault(1000, 1000, 0));
        assertEquals(R16IntTileLayer.NODATA, water.sampleOrNodata(1000, 1000));

        int[][] seam = {{15, 15}, {16, 15}, {15, 16}, {16, 16}};
        for (int[] cell : seam) {
            assertEquals(345610, reach.sampleOrDefault(cell[0], cell[1], 0), "reach " + cell[0] + "," + cell[1]);
            assertEquals(40000, station.sampleOrDefault(cell[0], cell[1], 0), "station " + cell[0] + "," + cell[1]);
            assertEquals(90, water.sampleOrNodata(cell[0], cell[1]), "water " + cell[0] + "," + cell[1]);
            int tileX = cell[0] / 16;
            int tileZ = cell[1] / 16;
            int local = (cell[1] % 16) * 16 + (cell[0] % 16);
            byte[] azimuth = Files.readAllBytes(root.resolve("water/river_flow_azimuth/%03d_%03d.u8".formatted(tileX, tileZ)));
            assertEquals(40, Byte.toUnsignedInt(azimuth[local]), "azimuth " + cell[0] + "," + cell[1]);
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int[] cell : seam) {
            int y = water.sampleOrNodata(cell[0], cell[1]);
            min = Math.min(min, y);
            max = Math.max(max, y);
        }
        assertEquals(0, max - min);
    }

    @Test
    void packedU32RegionReadsWideReachIds() throws IOException {
        Path root = temp("packed");
        Files.writeString(root.resolve("manifest.json"), manifest(4, 8, """
            "rivers": {
              "path": "water/rivers",
              "station_schema_version": 1,
              "reach_id_path": "water/river_reach_id",
              "reach_id_storage": "regions",
              "station_id_path": "water/river_station_id",
              "station_id_storage": "regions",
              "water_y_path": "water/river_water_y",
              "flow_azimuth_path": "water/river_flow_azimuth"
            }
            """));
        TileManifest manifest = TileManifest.load(root);
        assertTrue(manifest.riverReachIdStorage.usesRegions());
        assertEquals(".u32", manifest.riverReachIdExtension);
        assertEquals(".u32rg", manifest.riverReachIdStorage.regionExtension());
        assertEquals(0, manifest.riverReachIdStorage.missingTile());
        assertTrue(manifest.u32Storages.containsKey("river_reach_id"));
        assertFalse(manifest.u16Storages.containsKey("river_reach_id"));

        int cells = 4 * 4;
        ByteBuffer tile = ints(cells);
        putU32(tile, 0, 0, 65536);
        putU32(tile, 1, 0, 345610);
        Path region = root.resolve("water/river_reach_id/regions/000_000.u32rg");
        Files.createDirectories(region.getParent());
        // Stored default 7 fills empty region entries as a full uint32, not two uint16s.
        Files.write(region, packedRegion(4, 8, 4, 7, tile.array()));

        U32TileLayer reach = new U32TileLayer(manifest, "river_reach_id", manifest.riverReachIdStorage);
        U16TileLayer station = new U16TileLayer(manifest, "river_station_id", manifest.riverStationIdStorage);
        assertEquals(65536, reach.sampleOrDefault(0, 0, 0));
        assertEquals(345610, reach.sampleOrDefault(1, 0, 0));
        assertEquals(0, reach.sampleOrDefault(2, 0, 5));
        assertEquals(7, reach.sampleOrDefault(4, 0, 0));
        assertEquals(0, station.sampleOrDefault(0, 0, 5));
    }

    private static Path temp(String name) throws IOException {
        return Files.createTempDirectory("ukgeo-stations-" + name);
    }

    private static TileManifest load(Path root, String json) throws IOException {
        Files.writeString(root.resolve("manifest.json"), json);
        return TileManifest.load(root);
    }

    private static String manifest(int tile, int padded, String rivers) {
        return """
            {
              "format": "uk-raster-tiles-v1",
              "tile_size": %d,
              "world": {
                "width": %d,
                "depth": %d,
                "padded_width": %d,
                "padded_depth": %d,
                "minecraft_min_x": 0,
                "minecraft_min_z": 0,
                "minecraft_max_x": %d,
                "minecraft_max_z": %d
              },
              "height": {"sea_level_y": 64, "path": "height", "extension": ".r16"},
              %s
            }
            """.formatted(tile, padded, padded, padded, padded, padded - 1, padded - 1, rivers);
    }

    private static ByteBuffer shorts(int cells) {
        return ByteBuffer.allocate(cells * 2).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static ByteBuffer ints(int cells) {
        return ByteBuffer.allocate(cells * 4).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void putU16(ByteBuffer buffer, int localX, int localZ, int value) {
        int tile = (int) Math.sqrt(buffer.capacity() / 2.0);
        buffer.putShort((localZ * tile + localX) * 2, (short) (value & 0xFFFF));
    }

    private static void putU32(ByteBuffer buffer, int localX, int localZ, int value) {
        int tile = (int) Math.sqrt(buffer.capacity() / 4.0);
        buffer.putInt((localZ * tile + localX) * 4, value);
    }

    private static void putI16(ByteBuffer buffer, int localX, int localZ, int value) {
        int tile = (int) Math.sqrt(buffer.capacity() / 2.0);
        buffer.putShort((localZ * tile + localX) * 2, (short) value);
    }

    private static void writeBytes(Path path, ByteBuffer buffer) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, buffer.array());
    }

    private static byte[] packedRegion(int tileSize, int regionTiles, int bytesPerCell, int defaultValue, byte[] tile) {
        int tileBytes = tileSize * tileSize * bytesPerCell;
        if (tile.length != tileBytes) {
            throw new IllegalArgumentException("tile bytes " + tile.length + " != " + tileBytes);
        }
        int entryCount = regionTiles * regionTiles;
        int header = 28;
        int table = entryCount * 12;
        int payload = header + table;
        ByteBuffer out = ByteBuffer.allocate(payload + tile.length).order(ByteOrder.LITTLE_ENDIAN);
        out.put(new byte[] {'U', 'K', 'R', 'G'});
        out.putInt(1);
        out.putInt(tileSize);
        out.putInt(regionTiles);
        out.putInt(tileBytes);
        out.putInt(defaultValue);
        out.putInt(entryCount);
        out.putLong(payload);
        out.putInt(tile.length);
        for (int i = 1; i < entryCount; i++) {
            out.putLong(0L);
            out.putInt(0);
        }
        out.put(tile);
        return out.array();
    }
}
