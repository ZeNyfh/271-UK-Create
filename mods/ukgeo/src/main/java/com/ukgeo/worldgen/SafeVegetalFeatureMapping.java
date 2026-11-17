package com.ukgeo.worldgen;

import java.util.List;

/**
 * Biome-path → minecraft placed-feature path ids for UKGeo's safe vegetal placer.
 * Pure strings so unit tests need no registry bootstrap.
 */
public final class SafeVegetalFeatureMapping {
    private SafeVegetalFeatureMapping() {
    }

    /**
     * Placed-feature path segments (minecraft namespace) for the given biome path.
     * Empty means place no tree/vegetal features via the safe path.
     */
    public static List<String> featurePathsForBiome(String biomePath) {
        if (biomePath == null || biomePath.isEmpty()) {
            return List.of();
        }
        return switch (biomePath) {
            case "plains" -> List.of("trees_plains");
            case "forest" -> List.of("trees_birch_and_oak");
            case "pale_garden" -> List.of("pale_garden_vegetation", "pale_moss_patch");
            case "broadleaf_woodland" -> List.of("trees_birch_and_oak", "trees_sparse_jungle");
            case "dappled_forest" -> List.of("trees_dappled_forest");
            case "taiga", "conifer_woodland" -> List.of("trees_taiga");
            case "meadow" -> List.of("trees_meadow");
            case "flower_forest" -> List.of("trees_flower_forest");
            case "windswept_hills" -> List.of("trees_windswept_hills");
            case "swamp" -> List.of("trees_swamp");
            case "wetland" -> List.of("trees_swamp", "trees_sparse_jungle");
            case "windswept_savanna" -> List.of("trees_windswept_savanna");
            case "mountains" -> List.of("trees_windswept_hills");
            // Non-woodland UKGeo biomes: no tree features (prefer none).
            case "arable", "improved_grassland", "urban",
                 "neutral_grassland", "calcareous_grassland", "acid_grassland", "heath" -> List.of();
            default -> List.of();
        };
    }
}
