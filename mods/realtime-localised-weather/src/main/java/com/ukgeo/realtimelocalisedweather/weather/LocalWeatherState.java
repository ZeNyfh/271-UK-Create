package com.ukgeo.realtimelocalisedweather.weather;

public record LocalWeatherState(WeatherTileKey key, ServerWeatherSnapshot snapshot, boolean tropicalConversionApplied) {
    public boolean precipitating() {
        return snapshot != null && snapshot.resolvedPrecipitation().isPrecipitating();
    }

    public boolean raining() {
        return snapshot != null && snapshot.resolvedPrecipitation().isLiquid();
    }

    public boolean snowing() {
        return snapshot != null && snapshot.resolvedPrecipitation().isSnowy();
    }

    public boolean thundering() {
        return snapshot != null && snapshot.resolvedPrecipitation().supportsThunder();
    }
}
