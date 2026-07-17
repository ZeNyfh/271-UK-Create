package com.ukgeo.realtimelocalisedweather.weather;

public enum MeteorologicalPrecipitation {
    NONE,
    DRIZZLE,
    RAIN,
    SHOWERS,
    SNOW,
    SNOW_SHOWERS,
    FREEZING_DRIZZLE,
    FREEZING_RAIN,
    HAIL,
    THUNDERSTORM;

    public boolean isLiquid() {
        return switch (this) {
            case DRIZZLE, RAIN, SHOWERS, FREEZING_DRIZZLE, FREEZING_RAIN, THUNDERSTORM -> true;
            default -> false;
        };
    }

    public boolean isSnowLike() {
        return this == SNOW || this == SNOW_SHOWERS;
    }
}
