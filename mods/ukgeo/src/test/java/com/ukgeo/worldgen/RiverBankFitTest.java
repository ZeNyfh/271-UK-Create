package com.ukgeo.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RiverBankFitTest {
    @Test
    void flushWaterStaysAtBank() {
        assertEquals(100, RiverBankFit.resolveWaterSurface(100, 100));
    }

    @Test
    void smallOverflowRaisesBankPathThenWaterAtOrBelowRaised() {
        // d=1: raisedMinBank=min(99+2,100)=100 → water=100
        assertEquals(100, RiverBankFit.resolveWaterSurface(100, 99));
        // d=2: raisedMinBank=min(98+2,100)=100 → water=100
        assertEquals(100, RiverBankFit.resolveWaterSurface(100, 98));
    }

    @Test
    void largeOverflowLowersWaterWithoutRaisingBanksMoreThanTwo() {
        // d=3 → water = minBank
        assertEquals(97, RiverBankFit.resolveWaterSurface(100, 97));
        assertEquals(91, RiverBankFit.resolveWaterSurface(100, 91));
        assertEquals(100, RiverBankFit.resolveWaterSurface(110, 100)); // ridge → lowest bank
    }

    @Test
    void shallowBelowBankRaisesToFlush() {
        // Within MAX_BANK_RAISE below bank → raise water to flush.
        assertEquals(100, RiverBankFit.resolveWaterSurface(99, 100)); // raise 1
        assertEquals(100, RiverBankFit.resolveWaterSurface(98, 100)); // raise 2
        // More than MAX_BANK_RAISE below → cascade/canyon, leave unchanged.
        assertEquals(97, RiverBankFit.resolveWaterSurface(97, 100)); // 3 below stays
    }

    @Test
    void noAdjacentBankLeavesWater() {
        assertEquals(100, RiverBankFit.resolveWaterSurface(100, Integer.MAX_VALUE));
    }

    @Test
    void raiseBankCapsAtTwoAndTargetsWaterFlush() {
        assertEquals(100, RiverBankFit.raiseBankToward(99, 100));
        assertEquals(100, RiverBankFit.raiseBankToward(98, 100));
        assertEquals(100, RiverBankFit.raiseBankToward(100, 100));
        assertEquals(102, RiverBankFit.raiseBankToward(102, 100));
    }

    @Test
    void bedPreservesDepth() {
        assertEquals(95, RiverBankFit.bedForWater(100, 5, -64));
        assertEquals(-63, RiverBankFit.bedForWater(100, 200, -64));
    }
    @Test
    void bankCarveTargetCapsIncisionAndNeverRaises() {
        // Highland mountain stamp: near-bank incision capped.
        int original = 160;
        int water = 100;
        int near = RiverBankFit.bankCarveTarget(original, water, 0.5, RiverBankFit.BANK_CARVE_REACH);
        assertTrue(near < original);
        assertTrue(near >= original - RiverBankFit.MAX_BANK_INCISION_NEAR);
        int far = RiverBankFit.bankCarveTarget(original, water, RiverBankFit.BANK_CARVE_REACH, RiverBankFit.BANK_CARVE_REACH);
        assertTrue(far <= original);
        assertTrue(far >= original - RiverBankFit.maxBankIncision(RiverBankFit.BANK_CARVE_REACH, RiverBankFit.BANK_CARVE_REACH));
        assertEquals(98, RiverBankFit.bankCarveTarget(98, water, 1.0, RiverBankFit.BANK_CARVE_REACH));
        int prev = RiverBankFit.bankCarveTarget(original, water, 1.0, RiverBankFit.BANK_CARVE_REACH);
        for (double d = 2.0; d <= RiverBankFit.BANK_CARVE_REACH; d += 0.5) {
            int carved = RiverBankFit.bankCarveTarget(original, water, d, RiverBankFit.BANK_CARVE_REACH);
            assertTrue(carved <= original, "must not raise above original at d=" + d);
            assertTrue(carved >= prev - 1, "should trend upward inland at d=" + d);
            prev = Math.max(prev, carved);
        }
    }

    @Test
    void bankCarveTargetMountainStampDoesNotCanyon() {
        int carved = RiverBankFit.bankCarveTarget(160, 100, 1.0, RiverBankFit.BANK_CARVE_REACH);
        assertTrue(carved >= 160 - RiverBankFit.MAX_BANK_INCISION_NEAR, "carved=" + carved);
        assertTrue(carved <= 160);
    }

    @Test
    void cappedPlaneLiftsHighlandMountainStampOnly() {
        // Lowland / modest DEM: keep absolute baked water even when deep.
        assertEquals(100, RiverBankFit.cappedPlane(105, 100, 3));
        assertEquals(100, RiverBankFit.cappedPlane(120, 100, 3));
        assertEquals(-40, RiverBankFit.cappedPlane(10, -40, 3));
        // Highland with deep stamp: lift so DEM→bed ≤ MOUNTAIN_TOTAL_INCISION.
        int plane = RiverBankFit.cappedPlane(160, 100, 3);
        assertEquals(160, plane);
        int bed = RiverBankFit.bedForWater(plane, 3, -64);
        assertTrue(160 - bed <= RiverBankFit.MOUNTAIN_TOTAL_INCISION);
        assertTrue(160 - bed >= 1);
        // Depth is included in the total cap (depth 2 still ≤ 3 total).
        int plane2 = RiverBankFit.cappedPlane(160, 100, 2);
        int bed2 = RiverBankFit.bedForWater(plane2, 2, -64);
        assertTrue(160 - bed2 <= RiverBankFit.MOUNTAIN_TOTAL_INCISION);
    }

    @Test
    void mountainTotalIncisionIncludesBedDepth() {
        for (int depth = 1; depth <= 4; depth++) {
            int plane = RiverBankFit.cappedPlane(180, 90, depth);
            int bed = RiverBankFit.bedForWater(plane, depth, -64);
            assertTrue(180 - bed <= RiverBankFit.MOUNTAIN_TOTAL_INCISION,
                "depth=" + depth + " incision=" + (180 - bed));
            assertTrue(plane - bed >= 1);
        }
    }

    @Test
    void bankCarveTargetNearLowersModestlyFarIsHigher() {
        int near = RiverBankFit.bankCarveTarget(160, 100, 1.0, RiverBankFit.BANK_CARVE_REACH);
        int mid = RiverBankFit.bankCarveTarget(160, 100, 4.0, RiverBankFit.BANK_CARVE_REACH);
        int far = RiverBankFit.bankCarveTarget(160, 100, 7.5, RiverBankFit.BANK_CARVE_REACH);
        assertTrue(near < 160);
        assertTrue(far >= mid);
        assertTrue(far <= 160);
    }

    @Test
    void bankConformTargetRaisesLowBankNearWater() {
        assertEquals(100, RiverBankFit.bankConformTarget(98, 100, 1.0, 12));
    }

}
