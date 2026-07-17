package com.ukgeo.worldgen.geo;

import java.util.Optional;

public record UkGeoReference(
    String crs,
    int minecraftMinX,
    int minecraftMinZ,
    int minecraftMaxX,
    int minecraftMaxZ,
    double bngMinEasting,
    double bngMinNorthing,
    double bngMaxEasting,
    double bngMaxNorthing,
    int rasterWidth,
    int rasterDepth
) {
    public Optional<BngCoordinate> minecraftToBng(double minecraftX, double minecraftZ) {
        if (!contains(minecraftX, minecraftZ) || rasterWidth <= 0 || rasterDepth <= 0) {
            return Optional.empty();
        }
        double blockCenterX = minecraftX + 0.5D;
        double blockCenterZ = minecraftZ + 0.5D;
        double dataX = blockCenterX - minecraftMinX;
        double dataZ = blockCenterZ - minecraftMinZ;
        double easting = bngMinEasting + dataX * (bngMaxEasting - bngMinEasting) / rasterWidth;
        double northing = bngMaxNorthing - dataZ * (bngMaxNorthing - bngMinNorthing) / rasterDepth;
        return Optional.of(new BngCoordinate(easting, northing));
    }

    public Optional<Wgs84Coordinate> minecraftToWgs84(double minecraftX, double minecraftZ) {
        return minecraftToBng(minecraftX, minecraftZ).map(BngToWgs84::convert);
    }

    public boolean contains(double minecraftX, double minecraftZ) {
        return minecraftX >= minecraftMinX
            && minecraftX <= minecraftMaxX
            && minecraftZ >= minecraftMinZ
            && minecraftZ <= minecraftMaxZ;
    }
}
