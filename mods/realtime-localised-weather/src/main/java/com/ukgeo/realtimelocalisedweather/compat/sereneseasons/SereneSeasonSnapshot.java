package com.ukgeo.realtimelocalisedweather.compat.sereneseasons;

public record SereneSeasonSnapshot(
    boolean detected,
    boolean winter,
    boolean tropicalBiome,
    String season,
    String subSeason,
    String tropicalSeason
) {
    public static final SereneSeasonSnapshot ABSENT = new SereneSeasonSnapshot(false, false, false, "unknown", "unknown", "unknown");
}
