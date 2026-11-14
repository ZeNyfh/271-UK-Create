package com.ukgeo.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    }

    @Test
    void alreadyBelowBankUnchanged() {
        assertEquals(98, RiverBankFit.resolveWaterSurface(98, 100));
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
}
