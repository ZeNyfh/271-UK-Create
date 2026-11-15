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
    void bankCarveTargetFlushNearWaterAndNeverRaises() {
        int original = 105;
        int water = 100;
        assertEquals(100, RiverBankFit.bankCarveTarget(original, water, 0.5, RiverBankFit.BANK_CARVE_REACH));
        assertEquals(100, RiverBankFit.bankCarveTarget(original, water, 1.0, RiverBankFit.BANK_CARVE_REACH));
        int far = RiverBankFit.bankCarveTarget(original, water, RiverBankFit.BANK_CARVE_REACH, RiverBankFit.BANK_CARVE_REACH);
        assertTrue(far <= original && far >= water);
        assertEquals(98, RiverBankFit.bankCarveTarget(98, water, 1.0, RiverBankFit.BANK_CARVE_REACH));
        int prev = RiverBankFit.bankCarveTarget(original, water, 1.0, RiverBankFit.BANK_CARVE_REACH);
        for (double d = 2.0; d <= RiverBankFit.BANK_CARVE_REACH; d += 0.5) {
            int t = RiverBankFit.bankCarveTarget(original, water, d, RiverBankFit.BANK_CARVE_REACH);
            assertTrue(t <= original, "must not raise above original at d=" + d);
            assertTrue(t >= prev - 1, "should trend upward inland at d=" + d);
            prev = Math.max(prev, t);
        }
    }

    @Test
    void bankCarveTargetNearShelfFlushAndFarTalusRise() {
        assertEquals(100, RiverBankFit.bankCarveTarget(110, 100, 1.0, 12));
        assertEquals(100, RiverBankFit.bankCarveTarget(110, 100, 2.0, 12));
        int far = RiverBankFit.bankCarveTarget(110, 100, 11.5, 12);
        assertTrue(far > 100 && far <= 110);
    }

    @Test
    void bankConformTargetRaisesLowBankNearWater() {
        assertEquals(100, RiverBankFit.bankConformTarget(98, 100, 1.0, 12));
    }

}
