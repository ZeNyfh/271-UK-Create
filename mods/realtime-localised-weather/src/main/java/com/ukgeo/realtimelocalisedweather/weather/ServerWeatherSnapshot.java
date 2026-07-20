package com.ukgeo.realtimelocalisedweather.weather;

import java.time.Instant;

public record ServerWeatherSnapshot(
    Instant observedAt,
    double latitude,
    double longitude,
    int weatherCode,
    MeteorologicalPrecipitation precipitation,
    float precipitationRateMmPerHour,
    float rainRateMmPerHour,
    float snowfallRateCmPerHour,
    float temperatureCelsius,
    float relativeHumidity,
    float totalCloudCover,
    float lowCloudCover,
    float midCloudCover,
    float highCloudCover,
    float visibilityMetres,
    float windSpeedKmh,
    float windDirectionDegrees,
    float windGustKmh,
    ResolvedPrecipitation resolvedPrecipitation,
    GameplaySeverity gameplaySeverity,
    float thunderPotential,
    boolean stale,
    long revision
) {
    public boolean hasPrecipitation() {
        return resolvedPrecipitation.isPrecipitating()
            || precipitationRateMmPerHour > 0.001F
            || rainRateMmPerHour > 0.001F
            || snowfallRateCmPerHour > 0.001F;
    }
}
