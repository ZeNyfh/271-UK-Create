package com.ukgeo.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RiverBankFitTest {
    @Test
    void smoothingPreservesRiverValleysAndFadesIntoHillsides() {
        double raw = 611.8;
        double average = 635.364;
        assertEquals(raw, RiverBankFit.preserveValleyMetres(raw, average, 0, 2));
        assertEquals(raw, RiverBankFit.preserveValleyMetres(raw, average, 2.5, 2));
        assertEquals((raw + average) / 2,
            RiverBankFit.preserveValleyMetres(raw, average, 6.5, 2), 1e-10);
        assertEquals(average, RiverBankFit.preserveValleyMetres(raw, average, 10.5, 2));
        assertEquals(average, RiverBankFit.preserveValleyMetres(raw, average, 100, 2));
        // Smoothing that lowers a ridge still applies; this guard only prevents valley fill.
        assertEquals(600, RiverBankFit.preserveValleyMetres(raw, 600, 0, 2));
    }

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
    void bedCapPreservesLowlandValleysAndLimitsHighlandIncision() {
        assertEquals(97, RiverBankFit.capBedIncision(120, 97));
        assertEquals(-43, RiverBankFit.capBedIncision(10, -43));
        assertEquals(157, RiverBankFit.capBedIncision(160, 97));
        assertEquals(157, RiverBankFit.capBedIncision(160, 158 - 4));
        assertEquals(159, RiverBankFit.capBedIncision(160, 159));
        assertEquals(125, RiverBankFit.capBedIncision(128, 120));
        assertEquals(120, RiverBankFit.capBedIncision(127, 120));
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
