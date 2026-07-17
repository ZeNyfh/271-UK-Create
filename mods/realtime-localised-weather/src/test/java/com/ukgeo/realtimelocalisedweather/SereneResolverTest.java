package com.ukgeo.realtimelocalisedweather;

import com.ukgeo.realtimelocalisedweather.compat.sereneseasons.SerenePrecipitationResolver;
import com.ukgeo.realtimelocalisedweather.compat.sereneseasons.SereneSeasonSnapshot;
import com.ukgeo.realtimelocalisedweather.weather.MeteorologicalPrecipitation;
import com.ukgeo.realtimelocalisedweather.weather.ResolvedPrecipitation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class SereneResolverTest {
    @Test
    void winterRainBecomesSnowUnderDefaultPolicy() {
        var resolution = SerenePrecipitationResolver.resolve(MeteorologicalPrecipitation.RAIN, 4.0F, new SereneSeasonSnapshot(true, true, false, "WINTER", "MID_WINTER", "DRY"));
        assertEquals(ResolvedPrecipitation.SNOW, resolution.resolvedPrecipitation());
    }

    @Test
    void winterThunderRainBecomesThundersnow() {
        var resolution = SerenePrecipitationResolver.resolve(MeteorologicalPrecipitation.THUNDERSTORM, 2.0F, new SereneSeasonSnapshot(true, true, false, "WINTER", "LATE_WINTER", "DRY"));
        assertEquals(ResolvedPrecipitation.THUNDER_SNOW, resolution.resolvedPrecipitation());
    }

    @Test
    void winterClearRemainsClearAndSummerSnowRemainsSnow() {
        assertEquals(ResolvedPrecipitation.NONE, SerenePrecipitationResolver.resolve(MeteorologicalPrecipitation.NONE, -2.0F, new SereneSeasonSnapshot(true, true, false, "WINTER", "MID_WINTER", "DRY")).resolvedPrecipitation());
        assertEquals(ResolvedPrecipitation.SNOW, SerenePrecipitationResolver.resolve(MeteorologicalPrecipitation.SNOW, 15.0F, new SereneSeasonSnapshot(true, false, false, "SUMMER", "MID_SUMMER", "DRY")).resolvedPrecipitation());
    }

    @Test
    void tropicalWinterRainRemainsRainAndMissingSereneUsesRealData() {
        assertEquals(ResolvedPrecipitation.RAIN, SerenePrecipitationResolver.resolve(MeteorologicalPrecipitation.RAIN, 6.0F, new SereneSeasonSnapshot(true, true, true, "WINTER", "MID_WINTER", "WET")).resolvedPrecipitation());
        assertEquals(ResolvedPrecipitation.RAIN, SerenePrecipitationResolver.resolve(MeteorologicalPrecipitation.RAIN, 6.0F, SereneSeasonSnapshot.ABSENT).resolvedPrecipitation());
    }
}
