package com.ukgeo.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LakeBathymetryTest {
    @Test
    void shoreIsShallowAndInteriorDeepensSmoothly() {
        int shore = LakeBathymetry.depthBlocks(0.5, 1000, 2000);
        int near = LakeBathymetry.depthBlocks(3.0, 1000, 2000);
        int mid = LakeBathymetry.depthBlocks(8.0, 1000, 2000);
        int deep = LakeBathymetry.depthBlocks(18.0, 1000, 2000);
        assertEquals(1, shore);
        assertTrue(near >= shore);
        assertTrue(mid >= near - 1);
        assertTrue(deep >= mid - 1);
        assertTrue(deep <= LakeBathymetry.MAX_DEPTH);
    }

    @Test
    void adjacentColumnsDifferByAtMostOne() {
        int prev = LakeBathymetry.depthBlocks(0.5, 50, 50);
        for (int d = 1; d <= 20; d++) {
            int depth = LakeBathymetry.depthBlocks(d, 50, 50);
            assertTrue(Math.abs(depth - prev) <= 2, "d=" + d + " prev=" + prev + " depth=" + depth);
            prev = depth;
        }
    }

    @Test
    void deterministicAcrossCalls() {
        for (int i = 0; i < 40; i++) {
            int x = 100 + i * 3;
            int z = -200 - i * 5;
            assertEquals(LakeBathymetry.depthBlocks(6.5, x, z), LakeBathymetry.depthBlocks(6.5, x, z));
        }
    }

    @Test
    void noCheckerboardBetweenCardinalNeighbours() {
        int spikes = 0;
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                int here = LakeBathymetry.depthBlocks(10.0, x, z);
                int east = LakeBathymetry.depthBlocks(10.0, x + 1, z);
                int south = LakeBathymetry.depthBlocks(10.0, x, z + 1);
                if (Math.abs(here - east) > 1) {
                    spikes++;
                }
                if (Math.abs(here - south) > 1) {
                    spikes++;
                }
            }
        }
        assertTrue(spikes < 8, "spikes=" + spikes);
    }
}
