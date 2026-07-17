package com.ukgeo.worldgen.geo;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

public final class BngToWgs84 {
    private static final CRSFactory CRS_FACTORY = new CRSFactory();
    private static final CoordinateTransformFactory TRANSFORM_FACTORY = new CoordinateTransformFactory();
    private static final CoordinateReferenceSystem BNG = CRS_FACTORY.createFromParameters(
        "EPSG:27700",
        "+proj=tmerc +lat_0=49 +lon_0=-2 +k=0.9996012717 +x_0=400000 +y_0=-100000 +ellps=airy +datum=OSGB36 +units=m +no_defs"
    );
    private static final CoordinateReferenceSystem WGS84 = CRS_FACTORY.createFromParameters(
        "EPSG:4326",
        "+proj=longlat +datum=WGS84 +no_defs"
    );
    private static final CoordinateTransform BNG_TO_WGS84 = TRANSFORM_FACTORY.createTransform(BNG, WGS84);

    private BngToWgs84() {
    }

    public static Wgs84Coordinate convert(BngCoordinate coordinate) {
        ProjCoordinate source = new ProjCoordinate(coordinate.easting(), coordinate.northing());
        ProjCoordinate target = new ProjCoordinate();
        BNG_TO_WGS84.transform(source, target);
        return new Wgs84Coordinate(target.y, target.x);
    }
}
