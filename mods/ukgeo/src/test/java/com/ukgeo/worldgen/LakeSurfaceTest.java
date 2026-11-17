package com.ukgeo.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LakeSurfaceTest {
    @Test
    void overlappingShorelineScansClassifyRejectedCellsOnlyOnce() {
        var calls = new AtomicInteger();
        var lake = new LakeSurface(-64, -64, 4, 192, 192, (x, z) -> {
            calls.incrementAndGet();
            return LakeSurface.NODATA;
        });
        int probes = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                for (int dz = -32; dz <= 32; dz++) {
                    for (int dx = -32; dx <= 32; dx++) {
                        if (dx * dx + dz * dz > 32 * 32) continue;
                        assertFalse(lake.contains(x + dx, z + dz));
                        probes++;
                    }
                }
            }
        }
        // All 821,504 block probes visit the same small set of coarse source cells.
        assertEquals(821504, probes);
        assertEquals(360, calls.get(), "land / rejected river cells must be cached too");
        assertEquals(LakeSurface.NODATA, lake.waterY(0, 0));
        assertEquals(360, calls.get(), "waterY must share the negative sample cache");
    }

    @Test
    void warmQueriesAndUnrelatedColdSamplesDoNotWaitForLakePlaneResolution() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var lake = new LakeSurface(0, 0, 4, 64, 16, (x, z) -> {
            // A neighbour of the second lake is loaded while resolving its component.
            if (x == 38 && z == 6) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("source not released");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                }
            }
            return (x == 6 || x == 34) && z == 6 ? 72 : LakeSurface.NODATA;
        });
        assertEquals(72, lake.waterY(5, 5));
        assertTrue(lake.contains(5, 5));
        assertFalse(lake.contains(21, 5));
        try (var workers = Executors.newFixedThreadPool(2)) {
            var resolving = workers.submit(() -> lake.waterY(33, 5));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var independent = workers.submit(() -> {
                    assertTrue(lake.contains(5, 5));
                    assertFalse(lake.contains(21, 5));
                    assertFalse(lake.contains(49, 5));
                    assertEquals(72, lake.waterY(5, 5));
                    assertEquals(LakeSurface.NODATA, lake.waterY(21, 5));
                });
                independent.get(2, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            assertEquals(72, resolving.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void negativeSampleCacheEvictsOldCellsInsteadOfGrowingWithExploration() {
        int capacity = LakeSurface.MAX_NEGATIVE_SAMPLES;
        var calls = new AtomicInteger();
        var lake = new LakeSurface(0, 0, 1, capacity * 2, 1, (x, z) -> {
            calls.incrementAndGet();
            return LakeSurface.NODATA;
        });
        for (int x = 0; x < capacity * 2; x++) {
            assertFalse(lake.contains(x, 0));
        }
        assertEquals(capacity * 2, calls.get());
        // Read newest first so reloading evicted cells cannot evict an unread recent cell.
        for (int x = capacity * 2 - 1; x >= 0; x--) {
            assertFalse(lake.contains(x, 0));
        }
        assertEquals(capacity * 3, calls.get());
    }

    @Test
    void simultaneousColdQueriesKeepConnectedAndSeparateLakePlanes() throws Exception {
        var lake = new LakeSurface(-64, -64, 4, 192, 192,
            (x, z) -> x < 24 ? (z > 52 ? 90 : 72) : x >= 48 ? 96 : LakeSurface.NODATA);
        try (var workers = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 8; worker++) {
                int offset = worker;
                results.add(workers.submit(() -> {
                    for (int z = -60 + offset; z < 64; z += 8) {
                        for (int x = -60; x < 124; x++) {
                            int expected = x < 24 ? 72 : x >= 48 ? 96 : LakeSurface.NODATA;
                            assertEquals(expected, lake.waterY(x, z));
                            assertEquals(expected != LakeSurface.NODATA, lake.contains(x, z));
                        }
                    }
                }));
            }
            for (var result : results) result.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void reportedLakeAtMinus396433SharesOnePlaneAndHasRoundedRasterCorners() throws Exception {
        Map<String, Integer> samples = new HashMap<>();
        try (var reader = new BufferedReader(new InputStreamReader(
            getClass().getResourceAsStream("/river-qa/lake-coarse.csv"), StandardCharsets.UTF_8))) {
            reader.readLine();
            for (String line; (line = reader.readLine()) != null;) {
                String[] r = line.split(",");
                samples.put(r[0] + ":" + r[1], Integer.parseInt(r[2]));
            }
        }
        LakeSurface.Source source = (x, z) -> samples.getOrDefault(x + ":" + z, LakeSurface.NODATA);
        var lake = new LakeSurface(-52100, -73850, 4, 68462, 100000, source);
        assertEquals(72, lake.waterY(-396, 433));
        var report = new ArrayList<String>();
        report.add("x,z,raw_lake,rounded_lake,former_local_max_y,final_water_y");
        int trimmed = 0;
        int expanded = 0;
        for (int z = 360; z < 544; z++) {
            for (int x = -504; x < -324; x++) {
                int cx = -52100 + Math.floorDiv(x + 52100, 4) * 4 + 2;
                int cz = -73850 + Math.floorDiv(z + 73850, 4) * 4 + 2;
                boolean raw = source.sample(cx, cz) != LakeSurface.NODATA;
                boolean wet = lake.contains(x, z);
                if (raw && !wet) trimmed++;
                if (wet && !raw) expanded++;
                int old = raw ? source.sample(cx, cz) : LakeSurface.NODATA;
                if (raw) {
                    // Historical moving maximum, sampled on the coarse DEM fixture.
                    for (int dz = -6; dz <= 6; dz += 2) {
                        for (int dx = -6; dx <= 6; dx += 2) {
                            if (dx * dx + dz * dz > 36) continue;
                            int sx = -52100 + Math.floorDiv(x + dx + 52100, 4) * 4 + 2;
                            int sz = -73850 + Math.floorDiv(z + dz + 73850, 4) * 4 + 2;
                            old = Math.max(old, source.sample(sx, sz));
                        }
                    }
                }
                if (wet) assertEquals(72, lake.waterY(x, z));
                report.add(x + "," + z + "," + (raw ? 1 : 0) + "," + (wet ? 1 : 0) + ","
                    + (raw ? old : "") + "," + (wet ? lake.waterY(x, z) : ""));
            }
        }
        assertTrue(trimmed > 0, "convex coarse corners must still trim");
        assertTrue(expanded > 0, "concave land corners must fill onto land cells");
        Path out = Path.of("build/reports/river-qa/lake.csv");
        Files.createDirectories(out.getParent());
        Files.write(out, report, StandardCharsets.UTF_8);
    }

    @Test
    void connectedLakeKeepsOnePlaneAcrossChunksAndHighShoreSamples() {
        LakeSurface.Source source = (x, z) -> x < 64 && z < 64
            ? (x > 52 ? 90 : x == 2 && z == 2 ? 68 : 72) : LakeSurface.NODATA;
        var forward = new LakeSurface(-64, -64, 4, 192, 192, source);
        var reverse = new LakeSurface(-64, -64, 4, 192, 192, source);
        assertEquals(72, forward.waterY(0, 0));
        assertEquals(72, reverse.waterY(60, 60));
        for (int z = -60; z < 64; z++) {
            for (int x = -60; x < 64; x++) {
                assertEquals(72, forward.waterY(x, z));
                assertEquals(forward.waterY(x, z), reverse.waterY(x, z));
            }
        }
    }

    @Test
    void disconnectedLakesKeepSeparateElevations() {
        var lake = new LakeSurface(0, 0, 4, 80, 32,
            (x, z) -> x < 24 ? 72 : x >= 48 ? 96 : LakeSurface.NODATA);
        assertEquals(72, lake.waterY(12, 12));
        assertEquals(96, lake.waterY(60, 12));
        assertFalse(lake.contains(36, 12));
        assertEquals(LakeSurface.NODATA, lake.waterY(36, 12));
    }

    @Test
    void coverageTrimsConvexCornersAndFillsConcaveLand() {
        var lake = new LakeSurface(-8, -8, 4, 16, 16,
            (x, z) -> x >= 0 && x < 4 && z >= 0 && z < 4 ? 72 : LakeSurface.NODATA);
        // Convex corners of a lone wet cell trim; single-edge land stays dry.
        assertFalse(lake.contains(0, 0));
        assertFalse(lake.contains(3, 3));
        assertTrue(lake.contains(1, 1));
        assertTrue(lake.contains(2, 2));
        assertFalse(lake.contains(-1, 2));
        assertFalse(lake.contains(4, 2));

        // L-shaped water: concave inner land corner fills (~0.47 >= 0.375).
        var ell = new LakeSurface(-8, -8, 4, 24, 24, (x, z) -> {
            boolean wet = (x >= 0 && x < 4 && z >= 0 && z < 8)
                || (x >= 4 && x < 8 && z >= 0 && z < 4);
            return wet ? 72 : LakeSurface.NODATA;
        });
        assertTrue(ell.contains(4, 4));
        assertEquals(72, ell.waterY(4, 4));
        assertFalse(ell.contains(7, 7));
    }

    @Test
    void landAndMissingHeightCannotBecomeLakeWater() {
        var lake = new LakeSurface(-8, -8, 4, 16, 16, (x, z) -> LakeSurface.NODATA);
        assertFalse(lake.contains(0, 0));
        assertEquals(LakeSurface.NODATA, lake.waterY(0, 0));
        assertFalse(lake.contains(-9, 0));
    }
}
