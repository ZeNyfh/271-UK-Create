package com.ukgeo.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RiverLakeTransitionTest {
    @Test
    void mouthWeightIsOneAtEdgeAndZeroBeyondRadius() {
        assertEquals(1.0, RiverLakeTransition.mouthWeight(0.0, 32.0), 1e-9);
        assertEquals(0.0, RiverLakeTransition.mouthWeight(32.0, 32.0), 1e-9);
        assertEquals(0.0, RiverLakeTransition.mouthWeight(40.0, 32.0), 1e-9);
        double mid = RiverLakeTransition.mouthWeight(16.0, 32.0);
        assertTrue(mid > 0.0 && mid < 1.0);
        // smoothstep(0.5) = 0.5 → weight 0.5
        assertEquals(0.5, mid, 1e-9);
    }

    @Test
    void blendWaterYRoundsLerp() {
        assertEquals(70, RiverLakeTransition.blendWaterY(70, 80, 0.0));
        assertEquals(80, RiverLakeTransition.blendWaterY(70, 80, 1.0));
        assertEquals(75, RiverLakeTransition.blendWaterY(70, 80, 0.5));
        assertEquals(73, RiverLakeTransition.blendWaterY(70, 80, 0.25));
    }

    @Test
    void blendDepthDeepensTowardDeeperLake() {
        assertEquals(3, RiverLakeTransition.blendDepth(3, 10, 0.0));
        assertEquals(10, RiverLakeTransition.blendDepth(3, 10, 1.0));
        assertEquals(7, RiverLakeTransition.blendDepth(3, 10, 0.5));
        // Never shallows below river when lake is deeper.
        assertTrue(RiverLakeTransition.blendDepth(4, 8, 0.25) >= 4);
    }

    @Test
    void blendDepthFollowsShallowerLakeWithoutForcingMax() {
        assertEquals(5, RiverLakeTransition.blendDepth(5, 2, 0.0));
        assertEquals(2, RiverLakeTransition.blendDepth(5, 2, 1.0));
        assertEquals(4, RiverLakeTransition.blendDepth(5, 2, 0.5));
        assertTrue(RiverLakeTransition.blendDepth(5, 2, 0.75) < 5);
    }

    @Test
    void blendDepthClampsAtLeastOne() {
        assertEquals(1, RiverLakeTransition.blendDepth(1, 1, 0.5));
        assertEquals(1, RiverLakeTransition.blendDepth(1, 0, 1.0));
    }

    @Test
    void blendChannelHalfWidthWidensTowardApproach() {
        assertEquals(3, RiverLakeTransition.blendChannelHalfWidth(3, 11, 0.0));
        assertEquals(11, RiverLakeTransition.blendChannelHalfWidth(3, 11, 1.0));
        assertEquals(7, RiverLakeTransition.blendChannelHalfWidth(3, 11, 0.5));
        assertTrue(RiverLakeTransition.blendChannelHalfWidth(3, 11, 0.25) >= 3);
    }
}
