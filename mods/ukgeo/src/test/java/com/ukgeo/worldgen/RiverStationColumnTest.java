package com.ukgeo.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RiverStationColumnTest {
    @Test
    void disabledSchemaCarveIsNone() {
        RiverStationColumn.Shape shape = RiverStationColumn.resolve(false, 4, 80, true, 0, 3, 100, 4, -64);
        assertFalse(shape.influenced());
        assertFalse(shape.hasWater());
        assertEquals(100, shape.terrainY());
        assertEquals(100, shape.waterY());
    }

    @Test
    void nodataAndZeroReachDoNotCarve() {
        assertFalse(RiverStationColumn.resolve(true, 4, R16IntTileLayer.NODATA, true, 0, 3, 100, 4, -64).influenced());
        assertFalse(RiverStationColumn.resolve(true, 0, 80, true, 0, 3, 100, 4, -64).influenced());
        assertTrue(RiverStationColumn.bakedWaterY(R16IntTileLayer.NODATA).isEmpty());
        assertEquals(-40, RiverStationColumn.bakedWaterY(-40).getAsInt());
        assertEquals(-32767, RiverStationColumn.bakedWaterY(-32767).getAsInt());
    }

    @Test
    void stationStripWaterYIsFlatIncludingBanks() {
        int water = 77;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int dx = -8; dx <= 8; dx++) {
            double blocks = Math.abs(dx);
            RiverStationColumn.Shape shape = RiverStationColumn.resolve(true, 3, water, true, blocks, 3, 120, 4, -64);
            assertTrue(shape.influenced());
            assertEquals(water, shape.waterY());
            assertEquals(blocks <= 3, shape.hasWater());
            min = Math.min(min, shape.waterY());
            max = Math.max(max, shape.waterY());
        }
        assertEquals(0, max - min);
    }

    @Test
    void immediateBanksShareBakedYWithinOne() {
        int water = 77;
        RiverStationColumn.Shape left = RiverStationColumn.resolve(true, 9, water, true, 4.0, 3, 120, 4, -64);
        RiverStationColumn.Shape right = RiverStationColumn.resolve(true, 9, water, true, 4.0, 3, 118, 4, -64);
        assertFalse(left.hasWater());
        assertFalse(right.hasWater());
        assertEquals(water, left.waterY());
        assertEquals(water, right.waterY());
        assertTrue(Math.abs(left.terrainY() - right.terrainY()) <= 1);
        assertTrue(Math.abs(left.terrainY() - water) <= 1);
        assertTrue(Math.abs(right.terrainY() - water) <= 1);
    }

    @Test
    void diagonalSeamColumnsShareBakedWater() {
        int baked = 91;
        RiverStationColumn.Shape west = RiverStationColumn.resolve(true, 12, baked, true, 1, 4, 100, 3, -64);
        RiverStationColumn.Shape east = RiverStationColumn.resolve(true, 12, baked, true, 2, 4, 104, 3, -64);
        assertEquals(west.waterY(), east.waterY());
        assertEquals(0, Math.max(west.waterY(), east.waterY()) - Math.min(west.waterY(), east.waterY()));
        assertEquals(baked, west.waterY());
    }

    @Test
    void negativeWaterYIsKeptAndNotNodata() {
        RiverStationColumn.Shape shape = RiverStationColumn.resolve(true, 2, -40, true, 0, 3, 10, 3, -64);
        assertTrue(shape.hasWater());
        assertEquals(-40, shape.waterY());
        assertTrue(shape.terrainY() < shape.waterY());
        RiverStationColumn.Shape almost = RiverStationColumn.resolve(true, 2, -32767, true, 0, 3, 0, 2, -64);
        assertEquals(-32767, almost.waterY());
    }

    @Test
    void freshwaterNonCorridorStaysLake() {
        assertTrue(RiverStationColumn.freshwaterUsesLake(true, 0, true));
        assertTrue(RiverStationColumn.freshwaterUsesLake(true, 0, false));
        assertFalse(RiverStationColumn.freshwaterUsesLake(true, 5, false));
        assertTrue(RiverStationColumn.freshwaterUsesLake(false, 0, false));
        assertFalse(RiverStationColumn.freshwaterUsesLake(false, 0, true));
    }

    @Test
    void disabledMessageListsEveryMissingLayer() {
        List<String> problems = List.of(
            "station_schema_version=0 (required 1)",
            "reach_id_path",
            "station_id_path",
            "water_y_path",
            "flow_azimuth_path"
        );
        String message = RiverStationColumn.disabledMessage(problems);
        for (String problem : problems) {
            assertTrue(message.contains(problem), message);
        }
    }

    @Test
    void decodeTreatsOnlySentinelAsNodata() {
        assertEquals(80, R16IntTileLayer.decode((short) 80));
        assertEquals(-1, R16IntTileLayer.decode((short) -1));
        assertEquals(-40, R16IntTileLayer.decode((short) -40));
        assertEquals(-32767, R16IntTileLayer.decode((short) -32767));
        assertEquals(R16IntTileLayer.NODATA, R16IntTileLayer.decode((short) -32768));
    }
}
