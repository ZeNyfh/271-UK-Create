package com.ukgeo.realtimelocalisedweather.weather;

public enum ResolvedPrecipitation {
    NONE,
    DRIZZLE,
    RAIN,
    SNOW,
    SLEET,
    FREEZING_RAIN,
    HAIL,
    THUNDER_RAIN,
    THUNDER_SNOW;

    public boolean isPrecipitating() {
        return this != NONE;
    }

    public boolean isLiquid() {
        return this == DRIZZLE || this == RAIN || this == THUNDER_RAIN || this == FREEZING_RAIN || this == HAIL;
    }

    public boolean isSnowy() {
        return this == SNOW || this == SLEET || this == THUNDER_SNOW;
    }

    public boolean supportsThunder() {
        return this == THUNDER_RAIN || this == THUNDER_SNOW;
    }
}
