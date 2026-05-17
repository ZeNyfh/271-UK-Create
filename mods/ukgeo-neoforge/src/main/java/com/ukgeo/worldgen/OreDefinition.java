package com.ukgeo.worldgen;

public record OreDefinition(
    String name,
    String scoreLayer,
    String block,
    String deepslateBlock,
    int baseAttempts,
    int maxBonusAttempts,
    int minDepthBelowSurface,
    int maxDepthBelowSurface,
    int veinSize
) {
}

