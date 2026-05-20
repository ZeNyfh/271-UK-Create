package com.ukgeo.worldgen;

public record OreDefinition(
    String name,
    String scoreLayer,
    String block,
    String deepslateBlock,
    int baseAttempts,
    int maxBonusAttempts,
    int vanillaMinY,
    int vanillaMaxY,
    int veinSize
) {
    public boolean hasScoreLayer() {
        return scoreLayer != null && !scoreLayer.isBlank();
    }
}
