package com.ukgeo.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RiverWaterPlaneTest {
    @Test
    void robustLowestNearTenthPercentile() {
        int[] samples = {100, 101, 102, 110, 120};
        int plane = RiverWaterPlane.robustLowest(samples, samples.length);
        assertTrue(plane == 100 || plane == 101, "expected ~10th %ile near 100/101, was " + plane);
    }

    @Test
    void robustLowestTrueMinWhenSparse() {
        assertEquals(100, RiverWaterPlane.robustLowest(new int[] {100, 110, 120}, 3));
        assertEquals(95, RiverWaterPlane.robustLowest(new int[] {95}, 1));
    }

    @Test
    void bankSampleOffsetsTwoRingsAroundWater() {
        int waterRadius = 5;
        int[] dx = new int[32];
        int[] dz = new int[32];
        int count = RiverWaterPlane.bankSampleOffsets(waterRadius, dx, dz);
        assertTrue(count >= 16, "expected ≥16 offsets, was " + count);
        assertEquals(32, count);

        int ring1 = waterRadius + 2;
        int ring2 = waterRadius + 2 + Math.max(4, waterRadius);
        int nearRing1 = 0;
        int nearRing2 = 0;
        for (int i = 0; i < count; i++) {
            double r = Math.hypot(dx[i], dz[i]);
            if (Math.abs(r - ring1) <= 1.5) {
                nearRing1++;
            }
            if (Math.abs(r - ring2) <= 1.5) {
                nearRing2++;
            }
        }
        assertTrue(nearRing1 >= 12, "ring1 hits=" + nearRing1);
        assertTrue(nearRing2 >= 12, "ring2 hits=" + nearRing2);
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
        // Deterministic: same inputs → same depth (no jitter).
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

    @Test
    void planeFromSamplesSameCenterlineSamePlane() {
        // Documented contract: every water/bank column that shares a centerline station
        // uses planeFromSamples on the same bank-top ring → identical waterSurfaceY.
        int[] bankTops = {105, 100, 102, 110, 101, 108, 103, 99};
        int planeA = RiverWaterPlane.planeFromSamples(bankTops, bankTops.length);
        int planeB = RiverWaterPlane.planeFromSamples(bankTops.clone(), bankTops.length);
        assertEquals(planeA, planeB);
        assertEquals(RiverWaterPlane.robustLowest(bankTops, bankTops.length), planeA);
    }

    @Test
    void stabilizeWithNeighborsTakesMin() {
        // Adjacent mask cells with different robustLowest → min kills mid-channel ridge.
        assertEquals(98, RiverWaterPlane.stabilizeWithNeighbors(100, new int[] {99, 98, 101}, 3));
        assertEquals(100, RiverWaterPlane.stabilizeWithNeighbors(100, new int[] {}, 0));
        assertEquals(100, RiverWaterPlane.stabilizeWithNeighbors(100, new int[] {100, 102}, 2));
    }
}
