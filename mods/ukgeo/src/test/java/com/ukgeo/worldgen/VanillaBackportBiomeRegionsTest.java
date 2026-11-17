package com.ukgeo.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.ukgeo.worldgen.VanillaBackportBiomeRegions.RegionKind;
import org.junit.jupiter.api.Test;

class VanillaBackportBiomeRegionsTest {
    @Test
    void classifyCentersMatchNamedRegions() {
        // Pale centers
        assertEquals(RegionKind.PALE_GARDEN, VanillaBackportBiomeRegions.classify(275_000, 70_000)); // Dartmoor
        assertEquals(RegionKind.PALE_GARDEN, VanillaBackportBiomeRegions.classify(295_000, 142_500)); // Exmoor
        assertEquals(RegionKind.PALE_GARDEN, VanillaBackportBiomeRegions.classify(267_500, 350_000)); // Eryri
        assertEquals(RegionKind.PALE_GARDEN, VanillaBackportBiomeRegions.classify(165_000, 780_000)); // W Highlands Celtic
        assertEquals(RegionKind.PALE_GARDEN, VanillaBackportBiomeRegions.classify(-75_000, 300_000)); // W Ireland oak

        // Dappled centers
        assertEquals(RegionKind.DAPPLED_FOREST, VanillaBackportBiomeRegions.classify(300_000, 875_000)); // Highlands (E of pale)
        assertEquals(RegionKind.DAPPLED_FOREST, VanillaBackportBiomeRegions.classify(385_000, 465_000)); // Pennines
        assertEquals(RegionKind.DAPPLED_FOREST, VanillaBackportBiomeRegions.classify(332_500, 515_000)); // Lakes
        assertEquals(RegionKind.DAPPLED_FOREST, VanillaBackportBiomeRegions.classify(145_000, 395_000)); // NI uplands

        // Pale wins W Highlands overlap
        assertEquals(RegionKind.PALE_GARDEN, VanillaBackportBiomeRegions.classify(165_000, 790_000));

        assertEquals(RegionKind.NONE, VanillaBackportBiomeRegions.classify(500_000, 100_000)); // SE England
    }

    @Test
    void forestExpandPromotesEligibleClassesAndLeavesProtectedAlone() {
        double e = 275_000; // Dartmoor pale
        double n = 70_000;
        long salt = 0L;

        for (int cls = 3; cls <= 9; cls++) {
            // Same inputs always same result (determinism); at least one class promotes somewhere
            boolean a = VanillaBackportBiomeRegions.shouldExpandForest(e, n, cls, salt);
            boolean b = VanillaBackportBiomeRegions.shouldExpandForest(e, n, cls, salt);
            assertEquals(a, b);
        }

        // Scan a small grid: classes 3–9 must promote at least once inside priority AABB
        boolean anyPromote = false;
        for (int dx = 0; dx < 200; dx += 10) {
            for (int dn = 0; dn < 200; dn += 10) {
                if (VanillaBackportBiomeRegions.shouldExpandForest(e + dx, n + dn, 4, salt)) {
                    anyPromote = true;
                }
            }
        }
        assertTrue(anyPromote, "expected some class-4 cells in Dartmoor to expand");

        assertFalse(VanillaBackportBiomeRegions.shouldExpandForest(e, n, 0, salt));
        assertFalse(VanillaBackportBiomeRegions.shouldExpandForest(e, n, 10, salt));
        assertFalse(VanillaBackportBiomeRegions.shouldExpandForest(e, n, 11, salt));
        assertFalse(VanillaBackportBiomeRegions.shouldExpandForest(e, n, 12, salt)); // rocky never
        assertFalse(VanillaBackportBiomeRegions.shouldExpandForest(e, n, 1, salt)); // already woodland
        assertFalse(VanillaBackportBiomeRegions.shouldExpandForest(500_000, 100_000, 4, salt)); // outside priority
    }

    @Test
    void sulfurWalesSoftFilterAndValeCore() {
        assertTrue(VanillaBackportBiomeRegions.inSulfurWales(305_000, 185_000)); // Vale core
        assertTrue(VanillaBackportBiomeRegions.inSulfurValeCore(305_000, 185_000));
        assertTrue(VanillaBackportBiomeRegions.inSulfurWales(200_000, 300_000)); // soft Wales mid
        assertFalse(VanillaBackportBiomeRegions.inSulfurValeCore(200_000, 300_000));
        assertFalse(VanillaBackportBiomeRegions.inSulfurWales(400_000, 500_000)); // outside Wales
        assertFalse(VanillaBackportBiomeRegions.inSulfurWales(100_000, 100_000));
    }

    @Test
    void expandedWoodlandClassPrefersConiferInDappled() {
        assertEquals(2, VanillaBackportBiomeRegions.expandedWoodlandClass(385_000, 465_000)); // Pennines
        assertEquals(1, VanillaBackportBiomeRegions.expandedWoodlandClass(275_000, 70_000)); // Dartmoor pale
        assertEquals(1, VanillaBackportBiomeRegions.expandedWoodlandClass(-50_000, 100_000)); // expand-only IE → broadleaf
    }
}
