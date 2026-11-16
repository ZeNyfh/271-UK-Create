package com.ukgeo.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RiverWaterPlaneTest {
    @Test
    void stationPlaneNotRaisedByHighProvisionalBed() {
        int plane = 100;
        int highProvisionalBed = 108;
        assertTrue(highProvisionalBed + 1 > plane, "setup: provisional would wrongly raise water");
        int bed = RiverWaterPlane.bedUnderPlane(plane, 5, -64);
        assertEquals(95, bed);
        assertTrue(bed < plane);
        assertEquals(-63, RiverWaterPlane.bedUnderPlane(plane, 200, -64));
    }

    @Test
    void smoothBowlDepthEdgeShallowCenterDeeper() {
        double halfWidth = 4.0;
        int maxDepth = 5;
        int edge = RiverWaterPlane.smoothBowlDepth(0.5, halfWidth, maxDepth);
        int mid = RiverWaterPlane.smoothBowlDepth(halfWidth * 0.5, halfWidth, maxDepth);
        int center = RiverWaterPlane.smoothBowlDepth(halfWidth, halfWidth, maxDepth);
        assertTrue(edge >= 1 && edge <= maxDepth);
        assertTrue(edge <= mid, "edge should be shallower than mid: edge=" + edge + " mid=" + mid);
        assertTrue(mid <= center, "mid should be ≤ center: mid=" + mid + " center=" + center);
        assertTrue(center > edge, "center deeper than edge");
        assertEquals(center, RiverWaterPlane.smoothBowlDepth(halfWidth, halfWidth, maxDepth));
        assertEquals(edge, RiverWaterPlane.smoothBowlDepth(0.5, halfWidth, maxDepth));
    }

    @Test
    void conformBankTopCapsMountainIncision() {
        // DEM 160, baked water 100 must not open a 60-block canyon.
        int near = RiverWaterPlane.conformBankTop(160, 100, 1.0, RiverBankFit.BANK_CARVE_REACH);
        assertTrue(near >= 160 - RiverBankFit.MAX_BANK_INCISION_NEAR, "near=" + near);
        assertTrue(near <= 160);
        int far = RiverWaterPlane.conformBankTop(160, 100, RiverBankFit.BANK_CARVE_REACH, RiverBankFit.BANK_CARVE_REACH);
        assertTrue(far >= near, "far should be at least as high as near: near=" + near + " far=" + far);
        assertTrue(far <= 160);
    }

    @Test
    void conformBankTopModestValleyStillLowersNearWater() {
        int near = RiverWaterPlane.conformBankTop(110, 100, 1.0, RiverBankFit.BANK_CARVE_REACH);
        assertTrue(near < 110, "near=" + near);
        assertTrue(near >= 100, "should not go below water plane, near=" + near);
    }

    @Test
    void conformBankTopLowBankUnchangedOrLightRaise() {
        int raised = RiverWaterPlane.conformBankTop(98, 100, 1.0, 12.0);
        assertTrue(raised >= 98 && raised <= 100);
        assertEquals(98, RiverWaterPlane.conformBankTop(98, 100, 8.0, 12.0));
    }

    @Test
    void conformBankTopFarHighBankTalusNotFullCarve() {
        int far = RiverWaterPlane.conformBankTop(110, 100, 7.5, RiverBankFit.BANK_CARVE_REACH);
        assertTrue(far > 100 && far <= 110);
    }
}
