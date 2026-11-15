package com.ukgeo.worldgen;

/**
 * Vegetation-class densities for seagrass and kelp.
 * Kept off the chunk generator so unit tests can call it without bootstrapping Minecraft.
 * Class ids match the public vegetation constants:
 * BROADLEAF=1, CONIFER=2, ARABLE=3, IMPROVED=4, NEUTRAL=5, CALCAREOUS=6,
 * ACID=7, WETLAND=8, HEATH=9, FRESHWATER=10, URBAN=11, ROCKY=12.
 */
final class AquaticFloraChance {
    private AquaticFloraChance() {
    }

    static double aquaticSeagrassChance(int vegetationClass) {
        return switch (vegetationClass) {
            case 8, 10 -> 0.40;
            case 1, 2, 3, 4, 5, 6 -> 0.26;
            case 9, 7, 12 -> 0.13;
            case 11 -> 0.05;
            default -> 0.18;
        };
    }

    static double aquaticTallSeagrassChance(int vegetationClass) {
        return switch (vegetationClass) {
            case 8, 10 -> 0.14;
            case 1, 2, 3, 4, 5, 6 -> 0.09;
            case 9, 7, 12 -> 0.04;
            case 11 -> 0.015;
            default -> 0.08;
        };
    }

    static double aquaticKelpChance(int vegetationClass, int depth, boolean coastal, boolean steep) {
        if (depth < 4 || (steep && depth <= 2)) {
            return 0.0;
        }
        boolean coastalFit = coastal && depth >= 4;
        boolean deepInland = depth >= 5 && (
            vegetationClass == 10
                || vegetationClass == 8
                || coastal
        );
        if (!coastalFit && !deepInland) {
            return 0.0;
        }
        if (vegetationClass == 10 || vegetationClass == 8) {
            return 0.10;
        }
        if (coastal) {
            return 0.10;
        }
        return 0.08;
    }
}
