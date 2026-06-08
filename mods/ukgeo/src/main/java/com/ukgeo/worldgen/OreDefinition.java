package com.ukgeo.worldgen;

public record OreDefinition(
    String name,
    String scoreLayer,
    String block,
    String deepslateBlock,
    double baseAttempts,
    double maxBonusAttempts,
    int vanillaMinY,
    int vanillaMaxY,
    OreHeightProfile heightProfile,
    double vanillaPeakY,
    double vanillaSecondPeakY,
    int veinSize
) {
    public OreDefinition(
        String name,
        String scoreLayer,
        String block,
        String deepslateBlock,
        double baseAttempts,
        double maxBonusAttempts,
        int vanillaMinY,
        int vanillaMaxY,
        int veinSize
    ) {
        this(name, scoreLayer, block, deepslateBlock, baseAttempts, maxBonusAttempts, vanillaMinY, vanillaMaxY, OreHeightProfile.UNIFORM, 0.0, 0.0, veinSize);
    }

    public boolean hasScoreLayer() {
        return scoreLayer != null && !scoreLayer.isBlank();
    }

    public enum OreHeightProfile {
        UNIFORM,
        TRIANGLE,
        DEEP_BIASED,
        TWO_PEAKS
    }
}
