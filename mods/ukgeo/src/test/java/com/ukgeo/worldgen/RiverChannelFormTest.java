package com.ukgeo.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RiverChannelFormTest {
    @Test
    void bowlDepth01CenterFullEdgeZero() {
        assertEquals(1.0, RiverChannelForm.bowlDepth01(0.0), 1e-9);
        assertEquals(0.0, RiverChannelForm.bowlDepth01(1.0), 1e-9);
        double mid = RiverChannelForm.bowlDepth01(0.5);
        assertTrue(mid > 0.0 && mid < 1.0, "mid bowl should be in (0,1), was " + mid);
    }

    @Test
    void bankRiseZeroAtChannelThenIncreases() {
        assertEquals(0.0, RiverChannelForm.bankRise(0.0, 2.0, 3.0, 0.55), 1e-9);
        double near = RiverChannelForm.bankRise(0.5, 2.0, 3.0, 0.55);
        double mid = RiverChannelForm.bankRise(1.5, 2.0, 3.0, 0.55);
        double far = RiverChannelForm.bankRise(3.0, 2.0, 3.0, 0.55);
        assertTrue(near > 0.0);
        assertTrue(mid > near);
        assertTrue(far >= mid);
    }

    @Test
    void thinChannelCenterDeeperThanEdge() {
        for (int halfWidth : new int[] {1, 2}) {
            int edgeDepth = RiverChannelForm.bowlDepthBlocks(0.5, halfWidth, 4);
            int centerDepth = RiverChannelForm.bowlDepthBlocks(halfWidth, halfWidth, 4);
            assertTrue(
                edgeDepth < centerDepth,
                "halfWidth=" + halfWidth + " edge=" + edgeDepth + " center=" + centerDepth
            );
        }
    }

    @Test
    void effectiveHalfWidthMountainBrookFloor() {
        assertEquals(2, RiverChannelForm.effectiveHalfWidth(1, 2.0, 1));
        assertEquals(2, RiverChannelForm.effectiveHalfWidth(1, 2.5, 2));
        assertEquals(1, RiverChannelForm.effectiveHalfWidth(1, 1.0, 1));
        assertEquals(3, RiverChannelForm.effectiveHalfWidth(3, 2.0, 1));
    }
    @Test
    void depthJitterBoundedAndEdgesShallower() {
        int maxDepth = 6;
        double halfWidth = 3.0;
        for (int i = 0; i < 200; i++) {
            int x = i * 7;
            int z = i * 11;
            double edgeDistance = halfWidth;
            int base = RiverChannelForm.bowlDepthBlocks(edgeDistance, halfWidth, maxDepth);
            int jittered = RiverChannelForm.applyDepthJitter(base, maxDepth, x, z, edgeDistance, halfWidth);
            assertTrue(jittered >= base - 1 && jittered <= base + 1, "jitter out of range: " + jittered);
            assertTrue(jittered <= maxDepth);
            double shallowEdge = 0.5;
            int edgeBase = RiverChannelForm.bowlDepthBlocks(shallowEdge, halfWidth, maxDepth);
            int edgeJ = RiverChannelForm.applyDepthJitter(edgeBase, maxDepth, x, z, shallowEdge, halfWidth);
            int centerBase = RiverChannelForm.bowlDepthBlocks(halfWidth, halfWidth, maxDepth);
            int centerJ = RiverChannelForm.applyDepthJitter(centerBase, maxDepth, x, z, halfWidth, halfWidth);
            assertTrue(edgeJ <= centerJ, "edge should stay shallower than center after jitter");
        }
    }

    @Test
    void edgeOccupancyJitterRoughlyHalfBlock() {
        double min = 0.0;
        double max = 0.0;
        for (int i = 0; i < 64; i++) {
            double j = RiverChannelForm.edgeOccupancyJitter(i * 3, i * 5);
            min = Math.min(min, j);
            max = Math.max(max, j);
        }
        assertTrue(min <= -0.1 && max >= 0.1, "jitter range min=" + min + " max=" + max);
        assertTrue(min >= -0.5 && max <= 0.5);
    }

}
