package com.ukgeo.worldgen;

import java.util.List;
import java.util.OptionalInt;

/** Canonical river-station decisions. Water Y comes only from the baked int16 layer.
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
     * Freshwater lake ownership. Real lakes ({@code effectiveLake}) keep the lake plane
     * even when a river-station corridor stamp crosses them. Corridor cells that are not
     * effective lakes use baked stations. With stations off, river-artifact freshwater
     * is not given a lake plane either.
     */
    static boolean freshwaterUsesLake(
        boolean stationsEnabled,
        int reachId,
        boolean riverArtifact,
        boolean effectiveLake
    ) {
        if (effectiveLake) {
            return true;
        }
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
            // The station layer owns the channel's water elevation. Do not clamp it
            // to each column's raw DEM: small DEM depressions would otherwise become
            // repeated, artificial pools with both water and bed stepping down.
            int bed = RiverBankFit.capBedIncision(originalSurfaceY,
                RiverWaterPlane.bedUnderPlane(water, depth, minBuildY));
            // A mismatched corridor through high terrain cannot be made wet
            // without either inventing uphill water or excavating a canyon.
            if (bed >= water) {
                return Shape.none(originalSurfaceY);
            }
            return new Shape(true, true, bed, water);
        }
        // Outside the wet channel, retain the DEM safety clamp so bank columns
        // cannot carry unsupported water planes above their local surface.
        water = clampWaterToLocalSurface(water, originalSurfaceY);
        double dOut = distanceFound ? Math.max(0.5, blocks - waterRadius) : 1.0;
        int terrain = RiverWaterPlane.conformBankTop(originalSurfaceY, water, dOut, RiverBankFit.BANK_CARVE_REACH);
        terrain = Math.max(minBuildY + 1, terrain);
        return new Shape(false, true, terrain, water);
    }

    /**
     * Clamp a baked reference plane to local terrain for dry bank columns only.
     * Channel water must remain on the baked station plane so DEM depressions do not
     * create artificial water-level steps along an otherwise level river.
     */
    static int clampWaterToLocalSurface(int waterY, int originalSurfaceY) {
        if (waterY > originalSurfaceY) {
            return originalSurfaceY;
        }
        return waterY;
    }

    record Shape(boolean hasWater, boolean influenced, int terrainY, int waterY) {

        static Shape none(int surfaceY) {
            return new Shape(false, false, surfaceY, surfaceY);
        }
    }
}
