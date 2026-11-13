package com.ukgeo.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RiverBankFitTest {
    @Test
    void flushWaterDropsOneBelowBank() {
        assertEquals(99, RiverBankFit.resolveWaterSurface(100, 100));
    }

    @Test
    void smallOverflowRaisesBankPathThenWaterAtOrBelowRaisedMinusOne() {
        // d=1: raise bank toward 101 → raisedMin=101 → water stays 100
        assertEquals(100, RiverBankFit.resolveWaterSurface(100, 99));
        // d=2: raise by 2 → raisedMin=min(100,101)=100 → water=99
        assertEquals(99, RiverBankFit.resolveWaterSurface(100, 98));
    }

    @Test
    void largeOverflowLowersWaterWithoutRaisingBanksMoreThanTwo() {
        // d=3 → water = minBank - 1
        assertEquals(96, RiverBankFit.resolveWaterSurface(100, 97));
        assertEquals(90, RiverBankFit.resolveWaterSurface(100, 91));
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
    void raiseBankCapsAtTwoAndTargetsWaterPlusOne() {
        assertEquals(101, RiverBankFit.raiseBankToward(99, 100));
        assertEquals(100, RiverBankFit.raiseBankToward(98, 100));
        assertEquals(101, RiverBankFit.raiseBankToward(101, 100));
        assertEquals(102, RiverBankFit.raiseBankToward(102, 100));
    }

    @Test
    void bedPreservesDepth() {
        assertEquals(95, RiverBankFit.bedForWater(100, 5, -64));
        assertEquals(-63, RiverBankFit.bedForWater(100, 200, -64));
    }
}
