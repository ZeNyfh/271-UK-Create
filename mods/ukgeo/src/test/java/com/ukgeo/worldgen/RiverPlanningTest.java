package com.ukgeo.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Exercises the production finishing passes without starting Minecraft registries. */
class RiverPlanningTest {
    @Test
    void sourceMountainBanksKeepTheirDistanceBasedProfileAfterFinishing() throws Exception {
        // Complete 64x64 terrain sample around the latest reported mountain river.
        Map<String, ChunkTerrainPlanner.ColumnPlan[]> chunks = new LinkedHashMap<>();
        List<int[]> rows = new ArrayList<>();
        try (var reader = new BufferedReader(new InputStreamReader(
            getClass().getResourceAsStream("/river-qa/mountain-banks.csv"), StandardCharsets.UTF_8))) {
            reader.readLine();
            for (String line; (line = reader.readLine()) != null;) {
                int[] r = java.util.Arrays.stream(line.split(",")).mapToInt(Integer::parseInt).toArray();
                rows.add(r);
                double occupancy = r[5] * 0.5;
                if (!RiverBankFit.isMountainStamp(r[2], r[4])) {
                    occupancy += RiverChannelForm.edgeOccupancyJitter(r[0], r[1]);
                }
                var shape = RiverStationColumn.resolve(true, r[3], r[4], r[5] < 255,
                    occupancy, r[6], r[2], 2, -128);
                chunks.computeIfAbsent(chunkKey(r), k -> new ChunkTerrainPlanner.ColumnPlan[256])[index(r)]
                    = column(r[2], shape, r[7] != 0);
            }
        }
        Map<String, ChunkTerrainPlanner.ColumnPlan[]> before = new LinkedHashMap<>();
        chunks.forEach((key, columns) -> before.put(key, columns.clone()));
        chunks.values().forEach(columns -> ChunkTerrainPlanner.finishWaterPlanning(columns, -128, 639));

        int dryBanks = 0, changedBanks = 0, overCutBanks = 0, maxDrop = 0, wet = 0;
        List<String> report = new ArrayList<>();
        report.add("x,z,dem_y,planned_terrain_y,final_terrain_y,water_y,wet,bank");
        for (int[] r : rows) {
            var old = before.get(chunkKey(r))[index(r)];
            var now = chunks.get(chunkKey(r))[index(r)];
            boolean bank = old.river().influenced() && !old.river().hasWater();
            assertEquals(old.river().hasWater(), now.river().hasWater());
            assertEquals(old.river().waterSurfaceY(), now.river().waterSurfaceY());
            if (bank) {
                dryBanks++;
                if (old.terrainTop() != now.terrainTop()) changedBanks++;
                int drop = now.originalSurfaceY() - now.terrainTop();
                maxDrop = Math.max(maxDrop, drop);
                if (drop > RiverBankFit.MOUNTAIN_TOTAL_INCISION) overCutBanks++;
            }
            if (now.river().hasWater()) wet++;
            report.add(r[0] + "," + r[1] + "," + r[2] + "," + old.terrainTop() + ","
                + now.terrainTop() + "," + now.river().waterSurfaceY() + ","
                + (now.river().hasWater() ? 1 : 0) + "," + (bank ? 1 : 0));
        }
        writeReport("mountain-banks", report);
        assertEquals(4096, rows.size());
        assertTrue(dryBanks > 500 && wet > 100, "must cover banks and channel");
        assertEquals(0, overCutBanks, "bank incision budget exceeded; maximum drop=" + maxDrop);
        assertEquals(0, changedBanks, "finishing must retain the world-distance bank profile");
    }

    @Test
    void mountainBankProfileDoesNotDependOnChunkOrigin() {
        Map<String, Integer> heights = new LinkedHashMap<>();
        int compared = 0;
        for (int offset : new int[] {0, 8}) {
            var columns = new ChunkTerrainPlanner.ColumnPlan[256];
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int worldX = offset + x;
                    int worldZ = offset + z;
                    double distance = Math.abs(worldX - worldZ);
                    int water = 160 - Math.floorDiv(worldZ, 4);
                    // A steep rock bank beside the diagonal channel stresses repeated notch cuts.
                    int dem = water + (int) Math.round(Math.max(0, distance - 1) * 8);
                    var shape = RiverStationColumn.resolve(true, 1, water, true, distance, 2, dem, 2, -64);
                    columns[z * 16 + x] = column(dem, shape, true);
                }
            }
            ChunkTerrainPlanner.finishWaterPlanning(columns, -64, 639);
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    var column = columns[z * 16 + x];
                    if (column.river().hasWater()) continue;
                    assertTrue(column.originalSurfaceY() - column.terrainTop()
                        <= RiverBankFit.MOUNTAIN_TOTAL_INCISION, "bank cuts must not accumulate");
                    String key = (offset + x) + ":" + (offset + z);
                    Integer previous = heights.put(key, column.terrainTop());
                    if (previous != null) {
                        compared++;
                        assertEquals(previous.intValue(), column.terrainTop(), key);
                    }
                }
            }
        }
        assertTrue(compared > 10, "must compare overlapping dry banks");
    }

    @Test
    void sourceMountainValleyKeepsWaterAtAll232StationsAfterFinishing() throws Exception {
        // Actual DEM and installed station samples near -13605, -34286.
        // The neighbourhood average was measured with the preset's radius 20 / step 10.
        Map<Integer, Boolean> oldWetStations = new LinkedHashMap<>();
        Map<Integer, Boolean> wetStations = new LinkedHashMap<>();
        Map<String, ChunkTerrainPlanner.ColumnPlan[]> chunks = new LinkedHashMap<>();
        int oldDry = 0;
        int newDry = 0;
        int count = 0;
        try (var reader = new BufferedReader(new InputStreamReader(
            getClass().getResourceAsStream("/river-qa/mountain-valley.csv"), StandardCharsets.UTF_8))) {
            reader.readLine();
            for (String line; (line = reader.readLine()) != null;) {
                String[] r = line.split(",");
                int x = Integer.parseInt(r[0]), z = Integer.parseInt(r[1]);
                int reach = Integer.parseInt(r[2]), station = Integer.parseInt(r[3]);
                double source = Double.parseDouble(r[4]), average = Double.parseDouble(r[5]);
                int sourceY = Integer.parseInt(r[6]), smoothedY = Integer.parseInt(r[7]);
                int water = Integer.parseInt(r[8]);
                double protectedMetres = RiverBankFit.preserveValleyMetres(source, average, 0, 2);
                assertEquals(Math.min(source, average), protectedMetres);
                int top = average > source ? sourceY : smoothedY;
                var previous = RiverStationColumn.resolve(true, reach, water, true, 0, 2, smoothedY, 2, -128);
                var current = RiverStationColumn.resolve(true, reach, water, true, 0, 2, top, 2, -128);
                oldWetStations.merge(station, previous.hasWater(), (a, b) -> a || b);
                wetStations.merge(station, current.hasWater(), (a, b) -> a || b);
                if (!previous.hasWater()) oldDry++;
                if (!current.hasWater()) newDry++;
                if (current.hasWater()) {
                    assertEquals(water, current.waterY());
                    assertTrue(top - current.terrainY() <= RiverBankFit.MOUNTAIN_TOTAL_INCISION);
                }
                String key = Math.floorDiv(x, 16) + ":" + Math.floorDiv(z, 16);
                int index = Math.floorMod(z, 16) * 16 + Math.floorMod(x, 16);
                chunks.computeIfAbsent(key, k -> new ChunkTerrainPlanner.ColumnPlan[256])[index] = column(top, current);
                count++;
            }
        }
        assertEquals(1193, count);
        assertEquals(941, oldDry);
        assertEquals(6, newDry, "genuine source/profile mismatches still obey the incision limit");
        assertEquals(167, oldWetStations.values().stream().filter(wet -> !wet).count());
        assertEquals(232, wetStations.size());
        assertTrue(wetStations.values().stream().allMatch(Boolean::booleanValue));
        chunks.values().forEach(columns -> {
            int[] waters = new int[256];
            java.util.Arrays.fill(waters, Integer.MIN_VALUE);
            for (int i = 0; i < columns.length; i++) {
                // Only channel cells were recorded; fill the rest with uninfluenced dry terrain.
                if (columns[i] == null) columns[i] = column(330, RiverStationColumn.Shape.none(330));
                if (columns[i].river().hasWater()) waters[i] = columns[i].river().waterSurfaceY();
            }
            ChunkTerrainPlanner.finishWaterPlanning(columns, -128, 639);
            for (int i = 0; i < columns.length; i++) {
                if (waters[i] != Integer.MIN_VALUE) {
                    assertTrue(columns[i].river().hasWater());
                    assertEquals(waters[i], columns[i].river().waterSurfaceY());
                    assertTrue(columns[i].originalSurfaceY() - columns[i].terrainTop()
                        <= RiverBankFit.MOUNTAIN_TOTAL_INCISION);
                }
            }
        });
    }

    @Test
    void lakeFinishingCannotLiftWaterToAChunkLocalMaximumOrBed() {
        var columns = new ChunkTerrainPlanner.ColumnPlan[256];
        for (int i = 0; i < columns.length; i++) {
            var lake = new UkGeoChunkGenerator.RiverShape(true, true, 69, 72, null, true);
            columns[i] = new ChunkTerrainPlanner.ColumnPlan(72, 69, 72, false, false, lake, 10, null, null, true);
        }
        // A separate water body or a contaminated shoreline sample must not lift neighbours.
        var high = new UkGeoChunkGenerator.RiverShape(true, true, 79, 80, null, true);
        columns[0] = new ChunkTerrainPlanner.ColumnPlan(80, 79, 80, false, false, high, 10, null, null, true);
        ChunkTerrainPlanner.finishWaterPlanning(columns, -64, 2047);
        for (int i = 1; i < columns.length; i++) {
            assertEquals(72, columns[i].river().waterSurfaceY());
            assertEquals(69, columns[i].river().terrainSurfaceY());
        }
    }

    private static ChunkTerrainPlanner.ColumnPlan column(int dem, RiverStationColumn.Shape shape) {
        return column(dem, shape, false);
    }

    private static ChunkTerrainPlanner.ColumnPlan column(int dem, RiverStationColumn.Shape shape, boolean steep) {
        var river = new UkGeoChunkGenerator.RiverShape(
            shape.hasWater(), shape.influenced(), shape.terrainY(), shape.waterY(), null);
        return new ChunkTerrainPlanner.ColumnPlan(dem, shape.terrainY(), Math.max(dem, shape.waterY()),
            steep, false, river, 0, null, null, true);
    }

    @Test
    void mountainBedSmoothingKeepsIncisionBudgetAndCascadePlanes() throws Exception {
        var columns = new ChunkTerrainPlanner.ColumnPlan[256];
        int[] waters = new int[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = z * 16 + x;
                int water = z < 8 ? 150 : 146;
                int dem = 143 + x;
                var shape = RiverStationColumn.resolve(true, 1, water, true, 0, 3, dem, 5, -64);
                columns[i] = column(dem, shape);
                waters[i] = shape.hasWater() ? water : Integer.MIN_VALUE;
            }
        }
        ChunkTerrainPlanner.finishWaterPlanning(columns, -64, 2047);
        List<String> report = new ArrayList<>();
        report.add("x,z,dem_y,baked_water_y,final_water_y,final_bed_y,depth");
        for (int i = 0; i < columns.length; i++) {
            var c = columns[i];
            int baked = i / 16 < 8 ? 150 : 146;
            if (waters[i] == Integer.MIN_VALUE) {
                assertFalse(c.river().hasWater());
                assertEquals(c.originalSurfaceY(), c.terrainTop());
            } else {
                assertTrue(c.river().hasWater());
                assertEquals(waters[i], c.river().waterSurfaceY());
                assertTrue(c.originalSurfaceY() - c.terrainTop() <= RiverBankFit.MOUNTAIN_TOTAL_INCISION);
                assertTrue(c.terrainTop() < c.river().waterSurfaceY());
            }
            report.add(i % 16 + "," + i / 16 + "," + c.originalSurfaceY() + "," + baked + ","
                + (c.river().hasWater() ? c.river().waterSurfaceY() : "") + "," + c.terrainTop() + ","
                + (c.river().hasWater() ? c.river().waterSurfaceY() - c.terrainTop() : 0));
        }
        // Same centreline remains wet on both sides of the genuine four-block drop.
        assertEquals(150, columns[7 * 16].river().waterSurfaceY());
        assertEquals(146, columns[8 * 16].river().waterSurfaceY());
        writeReport("mountain", report);
    }

    @Test
    void spawnFixtureKeepsStationWaterAcrossDemDipsAndChunkBoundaries() throws Exception {
        List<int[]> rows = new ArrayList<>();
        try (var reader = new BufferedReader(new InputStreamReader(
            getClass().getResourceAsStream("/river-qa/spawn.csv"), StandardCharsets.UTF_8))) {
            reader.readLine();
            for (String line; (line = reader.readLine()) != null;) {
                rows.add(java.util.Arrays.stream(line.split(",")).mapToInt(Integer::parseInt).toArray());
            }
        }
        Map<String, ChunkTerrainPlanner.ColumnPlan[]> chunks = new LinkedHashMap<>();
        for (int[] r : rows) {
            var shape = RiverStationColumn.resolve(true, r[3], r[5], true,
                r[6] / 2.0 + RiverChannelForm.edgeOccupancyJitter(r[0], r[1]), r[7], r[2], 3, -64);
            chunks.computeIfAbsent(chunkKey(r), k -> new ChunkTerrainPlanner.ColumnPlan[256])[index(r)] = column(r[2], shape);
        }
        chunks.values().forEach(c -> ChunkTerrainPlanner.finishWaterPlanning(c, -64, 2047));
        List<String> report = new ArrayList<>();
        report.add("x,z,dem_y,reach_id,station_id,baked_water_y,old_clamped_water_y,final_water_y,final_bed_y,depth");
        int formerlyLowered = 0;
        int wet = 0;
        for (int[] r : rows) {
            var c = chunks.get(chunkKey(r))[index(r)];
            if (!c.river().hasWater()) {
                continue;
            }
            wet++;
            assertEquals(r[5], c.river().waterSurfaceY(), "x=" + r[0] + " z=" + r[1]);
            assertTrue(c.terrainTop() < r[5]);
            if (r[2] < r[5]) {
                formerlyLowered++;
            }
            report.add(r[0] + "," + r[1] + "," + r[2] + "," + r[3] + "," + r[4] + "," + r[5] + ","
                + Math.min(r[2], r[5]) + "," + c.river().waterSurfaceY() + "," + c.terrainTop() + ","
                + (c.river().waterSurfaceY() - c.terrainTop()));
        }
        assertTrue(wet > 500, "fixture must exercise the channel");
        assertTrue(formerlyLowered > 100, "fixture must reproduce the former DEM clamp dips");
        writeReport("spawn", report);
    }

    private static void writeReport(String name, List<String> report) throws Exception {
        Path out = Path.of("build/reports/river-qa/" + name + ".csv");
        Files.createDirectories(out.getParent());
        Files.write(out, report, StandardCharsets.UTF_8);
    }

    private static String chunkKey(int[] r) {
        return Math.floorDiv(r[0], 16) + ":" + Math.floorDiv(r[1], 16);
    }

    private static int index(int[] r) {
        return Math.floorMod(r[1], 16) * 16 + Math.floorMod(r[0], 16);
    }
}
