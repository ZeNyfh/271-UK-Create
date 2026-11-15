package com.ukgeo.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AquaticFloraChanceTest {
    private static final double EPS = 1e-9;

    // Same ids as AquaticFloraChance (public vegetation constants).
    private static final int BROADLEAF = 1;
    private static final int CONIFER = 2;
    private static final int ARABLE = 3;
    private static final int IMPROVED = 4;
    private static final int NEUTRAL = 5;
    private static final int CALCAREOUS = 6;
    private static final int ACID = 7;
    private static final int WETLAND = 8;
    private static final int HEATH = 9;
    private static final int FRESHWATER = 10;
    private static final int URBAN = 11;
    private static final int ROCKY = 12;

    @Test
    void seagrassChanceByVegetationClass() {
        assertEquals(0.40, AquaticFloraChance.aquaticSeagrassChance(WETLAND), EPS);
        assertEquals(0.40, AquaticFloraChance.aquaticSeagrassChance(FRESHWATER), EPS);
        assertEquals(0.26, AquaticFloraChance.aquaticSeagrassChance(BROADLEAF), EPS);
        assertEquals(0.26, AquaticFloraChance.aquaticSeagrassChance(CONIFER), EPS);
        assertEquals(0.26, AquaticFloraChance.aquaticSeagrassChance(ARABLE), EPS);
        assertEquals(0.26, AquaticFloraChance.aquaticSeagrassChance(IMPROVED), EPS);
        assertEquals(0.26, AquaticFloraChance.aquaticSeagrassChance(NEUTRAL), EPS);
        assertEquals(0.26, AquaticFloraChance.aquaticSeagrassChance(CALCAREOUS), EPS);
        assertEquals(0.13, AquaticFloraChance.aquaticSeagrassChance(HEATH), EPS);
        assertEquals(0.13, AquaticFloraChance.aquaticSeagrassChance(ACID), EPS);
        assertEquals(0.13, AquaticFloraChance.aquaticSeagrassChance(ROCKY), EPS);
        assertEquals(0.05, AquaticFloraChance.aquaticSeagrassChance(URBAN), EPS);
        assertEquals(0.18, AquaticFloraChance.aquaticSeagrassChance(0), EPS);
    }

    @Test
    void tallSeagrassChanceByVegetationClass() {
        assertEquals(0.14, AquaticFloraChance.aquaticTallSeagrassChance(WETLAND), EPS);
        assertEquals(0.14, AquaticFloraChance.aquaticTallSeagrassChance(FRESHWATER), EPS);
        assertEquals(0.09, AquaticFloraChance.aquaticTallSeagrassChance(BROADLEAF), EPS);
        assertEquals(0.09, AquaticFloraChance.aquaticTallSeagrassChance(CONIFER), EPS);
        assertEquals(0.09, AquaticFloraChance.aquaticTallSeagrassChance(ARABLE), EPS);
        assertEquals(0.09, AquaticFloraChance.aquaticTallSeagrassChance(IMPROVED), EPS);
        assertEquals(0.09, AquaticFloraChance.aquaticTallSeagrassChance(NEUTRAL), EPS);
        assertEquals(0.09, AquaticFloraChance.aquaticTallSeagrassChance(CALCAREOUS), EPS);
        assertEquals(0.04, AquaticFloraChance.aquaticTallSeagrassChance(HEATH), EPS);
        assertEquals(0.04, AquaticFloraChance.aquaticTallSeagrassChance(ACID), EPS);
        assertEquals(0.04, AquaticFloraChance.aquaticTallSeagrassChance(ROCKY), EPS);
        assertEquals(0.015, AquaticFloraChance.aquaticTallSeagrassChance(URBAN), EPS);
        assertEquals(0.08, AquaticFloraChance.aquaticTallSeagrassChance(0), EPS);
    }

    @Test
    void kelpSkippedOnShallowSteepAndInlandGrassland() {
        assertEquals(0.0, AquaticFloraChance.aquaticKelpChance(FRESHWATER, 3, true, false), EPS);
        assertEquals(0.0, AquaticFloraChance.aquaticKelpChance(FRESHWATER, 1, false, false), EPS);
        assertEquals(0.0, AquaticFloraChance.aquaticKelpChance(WETLAND, 2, true, true), EPS);
        assertEquals(0.0, AquaticFloraChance.aquaticKelpChance(NEUTRAL, 8, false, false), EPS);
        assertEquals(0.0, AquaticFloraChance.aquaticKelpChance(IMPROVED, 6, false, false), EPS);
        assertEquals(0.0, AquaticFloraChance.aquaticKelpChance(CALCAREOUS, 4, false, true), EPS);
        assertEquals(0.0, AquaticFloraChance.aquaticKelpChance(FRESHWATER, 4, false, false), EPS);
    }

    @Test
    void kelpAllowedOnCoastalDepth4AndFreshwaterDepth5() {
        assertEquals(0.10, AquaticFloraChance.aquaticKelpChance(NEUTRAL, 4, true, false), EPS);
        assertEquals(0.10, AquaticFloraChance.aquaticKelpChance(HEATH, 6, true, false), EPS);
        assertEquals(0.10, AquaticFloraChance.aquaticKelpChance(FRESHWATER, 5, false, false), EPS);
        assertEquals(0.10, AquaticFloraChance.aquaticKelpChance(FRESHWATER, 9, false, false), EPS);
        assertEquals(0.10, AquaticFloraChance.aquaticKelpChance(WETLAND, 5, false, false), EPS);
        assertEquals(0.10, AquaticFloraChance.aquaticKelpChance(WETLAND, 4, true, false), EPS);
    }
}
