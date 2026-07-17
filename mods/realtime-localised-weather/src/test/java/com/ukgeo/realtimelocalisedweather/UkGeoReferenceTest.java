package com.ukgeo.realtimelocalisedweather;

import com.ukgeo.worldgen.geo.BngCoordinate;
import com.ukgeo.worldgen.geo.BngToWgs84;
import com.ukgeo.worldgen.geo.UkGeoReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class UkGeoReferenceTest {
    private static final UkGeoReference REFERENCE = new UkGeoReference(
        "EPSG:27700",
        -26050,
        -36925,
        7411,
        13074,
        -220000.0,
        0.0,
        650000.0,
        1300000.0,
        33462,
        50000
    );

    @Test
    void minecraftCoordinatesConvertToBngUsingManifestBounds() {
        BngCoordinate coordinate = REFERENCE.minecraftToBng(0.0, 0.0).orElseThrow();
        assertEquals(457303.6579, coordinate.easting(), 0.05);
        assertEquals(339937.0, coordinate.northing(), 0.1);
    }

    @Test
    void conversionUsesBlockCentresAndRejectsOutOfBounds() {
        assertTrue(REFERENCE.minecraftToBng(-26050, -36925).isPresent());
        assertTrue(REFERENCE.minecraftToBng(-26051, -36925).isEmpty());
        assertTrue(REFERENCE.minecraftToBng(7412, 13074).isEmpty());
    }

    @Test
    void bngToWgs84MatchesExternalReferenceLocations() {
        var greenwich = BngToWgs84.convert(new BngCoordinate(538890.0, 177320.0));
        var edinburgh = BngToWgs84.convert(new BngCoordinate(325897.0, 673996.0));
        var belfast = BngToWgs84.convert(new BngCoordinate(146000.0, 529000.0));

        assertEquals(51.4777955674, greenwich.latitude(), 0.00002);
        assertEquals(-0.0014016475, greenwich.longitude(), 0.00002);
        assertEquals(55.9532532408, edinburgh.latitude(), 0.00002);
        assertEquals(-3.1883020483, edinburgh.longitude(), 0.00002);
        assertEquals(54.5921591034, belfast.latitude(), 0.00002);
        assertEquals(-5.9333441969, belfast.longitude(), 0.00002);
    }
}
