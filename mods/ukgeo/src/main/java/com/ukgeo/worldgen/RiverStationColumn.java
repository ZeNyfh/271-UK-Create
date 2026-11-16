package com.ukgeo.worldgen;

import java.util.List;
import java.util.OptionalInt;

/**
 * Canonical river-station decisions. Water Y comes only from the baked int16 layer.
 * A disabled schema, nodata water, or a zero reach drops the column with no DEM fallback.
 */
final class RiverStationColumn {
    static final int REQUIRED_SCHEMA = 1;
    static final int WATER_NODATA = R16IntTileLayer.NODATA;

    private RiverStationColumn() {
    }

    static String disabledMessage(List<String> problems) {
        return "UKGeo river carve disabled: station schema/layers missing or incompatible: " + String.join(", ", problems);
    }

    /** Baked Minecraft water Y. Only {@link #WATER_NODATA} is empty; other negatives are real heights. */
    static OptionalInt bakedWaterY(int rawWaterY) {
        if (rawWaterY == WATER_NODATA) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(rawWaterY);
    }

    /**
     * Freshwater outside a baked corridor stays a lake.
     * With stations off, river-artifact freshwater is not given a lake plane either.
     */
    static boolean freshwaterUsesLake(boolean stationsEnabled, int reachId, boolean riverArtifact) {
        if (!stationsEnabled) {
            return !riverArtifact;
        }
        return reachId == 0;
    }

    static Shape resolve(
        boolean carveEnabled,
        int reachId,
        int rawWaterY,
        boolean distanceFound,
        double blocks,
        int waterRadius,
        int originalSurfaceY,
        int depth,
        int minBuildY
    ) {
        if (!carveEnabled || reachId == 0) {
            return Shape.none(originalSurfaceY);
        }
        OptionalInt baked = bakedWaterY(rawWaterY);
        if (baked.isEmpty()) {
            return Shape.none(originalSurfaceY);
        }
        int water = baked.getAsInt();
        boolean inChannel = distanceFound && blocks <= waterRadius;
        if (inChannel) {
            int bed = RiverWaterPlane.bedUnderPlane(water, depth, minBuildY);
            return new Shape(true, true, bed, water);
        }
        double dOut = distanceFound ? Math.max(0.5, blocks - waterRadius) : 1.0;
        int terrain = RiverWaterPlane.conformBankTop(originalSurfaceY, water, dOut, RiverBankFit.BANK_CARVE_REACH);
        terrain = Math.max(minBuildY + 1, terrain);
        return new Shape(false, true, terrain, water);
    }

    record Shape(boolean hasWater, boolean influenced, int terrainY, int waterY) {
        static Shape none(int surfaceY) {
            return new Shape(false, false, surfaceY, surfaceY);
        }
    }
}
