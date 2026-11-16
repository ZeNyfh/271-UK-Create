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
    void conformBankTopHighBankNearWaterFlushesToPlane() {
        assertEquals(100, RiverWaterPlane.conformBankTop(110, 100, 1.0, 12.0));
        assertEquals(100, RiverWaterPlane.conformBankTop(110, 100, 2.0, 12.0));
    }

    @Test
    void conformBankTopLowBankUnchangedOrLightRaise() {
        int raised = RiverWaterPlane.conformBankTop(98, 100, 1.0, 12.0);
        assertTrue(raised >= 98 && raised <= 100);
        assertEquals(98, RiverWaterPlane.conformBankTop(98, 100, 8.0, 12.0));
    }

    @Test
    void conformBankTopFarHighBankTalusNotFullCarve() {
        int far = RiverWaterPlane.conformBankTop(110, 100, 11.5, 12.0);
        assertTrue(far > 100 && far <= 110);
    }
}
