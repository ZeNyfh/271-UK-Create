package com.ukgeo.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SurfaceGeologyExposureTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void rejectedSteepGeologyUsesSoilAndStoneInsteadOfOriginalRock() {
        var rejectedExposure = Blocks.GRASS_BLOCK.defaultBlockState();

        assertEquals(
            Blocks.DIRT.defaultBlockState(),
            ChunkTerrainPlanner.steepSubsurfaceState(99, 100, rejectedExposure)
        );
        assertEquals(
            Blocks.DIRT.defaultBlockState(),
            ChunkTerrainPlanner.steepSubsurfaceState(97, 100, rejectedExposure)
        );
        assertEquals(
            Blocks.STONE.defaultBlockState(),
            ChunkTerrainPlanner.steepSubsurfaceState(96, 100, rejectedExposure)
        );
    }

    @Test
    void permittedSteepGeologyRemainsExposed() {
        var permittedExposure = Blocks.SMOOTH_BASALT.defaultBlockState();
        assertEquals(
            permittedExposure,
            ChunkTerrainPlanner.steepSubsurfaceState(96, 100, permittedExposure)
        );
    }

    @Test
    void valueNoiseIsDeterministicAndBounded() {
        double a = SurfaceExposureNoise.valueNoise2(12.3, -4.5, 42);
        double b = SurfaceExposureNoise.valueNoise2(12.3, -4.5, 42);
        assertEquals(a, b);
        assertTrue(a >= 0.0 && a <= 1.0);
    }

    @Test
    void valueNoiseIsSpatiallyCoherentNotIndependentSpeckles() {
        double localSum = 0.0;
        double farSum = 0.0;
        int n = 200;
        for (int i = 0; i < n; i++) {
            double x = i * 3.1;
            double z = i * 1.7;
            double here = SurfaceExposureNoise.valueNoise2(x, z, 7);
            double near = SurfaceExposureNoise.valueNoise2(x + 0.15, z + 0.1, 7);
            double far = SurfaceExposureNoise.valueNoise2(x + 40.0, z - 35.0, 7);
            localSum += Math.abs(here - near);
            farSum += Math.abs(here - far);
        }
        assertTrue(localSum / n < 0.12, "localMean=" + (localSum / n));
        assertTrue(farSum / n > localSum / n * 2.0, "far=" + (farSum / n) + " local=" + (localSum / n));
    }

    @Test
    void exposureThresholdFormsPatchesNotSinglePixels() {
        int size = 64;
        boolean[][] on = new boolean[size][size];
        int seed = "minecraft:smooth_basalt".hashCode();
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                on[z][x] = SurfaceExposureNoise.allowExposure(x, z, seed);
            }
        }
        boolean[][] seen = new boolean[size][size];
        int components = 0;
        int singles = 0;
        int totalOn = 0;
        int[] dz = {-1, 1, 0, 0};
        int[] dx = {0, 0, -1, 1};
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                if (!on[z][x] || seen[z][x]) {
                    continue;
                }
                components++;
                int count = 0;
                java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
                q.add(new int[] {z, x});
                seen[z][x] = true;
                while (!q.isEmpty()) {
                    int[] c = q.removeFirst();
                    count++;
                    totalOn++;
                    for (int i = 0; i < 4; i++) {
                        int nz = c[0] + dz[i];
                        int nx = c[1] + dx[i];
                        if (nz < 0 || nx < 0 || nz >= size || nx >= size || seen[nz][nx] || !on[nz][nx]) {
                            continue;
                        }
                        seen[nz][nx] = true;
                        q.add(new int[] {nz, nx});
                    }
                }
                if (count == 1) {
                    singles++;
                }
            }
        }
        assertTrue(totalOn > 0, "expected some exposure cells");
        assertTrue(singles * 2 <= components, "singles=" + singles + " components=" + components);
        assertTrue(totalOn >= components, "on=" + totalOn + " components=" + components);
    }
}
