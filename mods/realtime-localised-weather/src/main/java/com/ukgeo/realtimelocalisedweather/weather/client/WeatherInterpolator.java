package com.ukgeo.realtimelocalisedweather.weather.client;

import com.ukgeo.realtimelocalisedweather.config.ClientWeatherConfig;
import com.ukgeo.realtimelocalisedweather.weather.ResolvedPrecipitation;
import com.ukgeo.realtimelocalisedweather.weather.ServerWeatherSnapshot;
import com.ukgeo.realtimelocalisedweather.weather.WeatherMath;

public final class WeatherInterpolator {
    private WeatherInterpolator() {
    }

    public static float temporalLerpFactor(long transitionStartedAtMillis, long nowMillis) {
        double duration = Math.max(1.0D, ClientWeatherConfig.transitionSeconds()) * 1000.0D;
        return WeatherMath.clamp01((float) ((nowMillis - transitionStartedAtMillis) / duration));
    }

    public static float interpolateRate(ClientWeatherTile tile, long nowMillis) {
        float delta = temporalLerpFactor(tile.transitionStartedAtMillis(), nowMillis);
        return WeatherMath.lerp(tile.previous().precipitationRateMmPerHour(), tile.current().precipitationRateMmPerHour(), delta);
    }

    public static float interpolateCloud(ClientWeatherTile tile, long nowMillis) {
        float delta = temporalLerpFactor(tile.transitionStartedAtMillis(), nowMillis);
        return WeatherMath.lerp(tile.previous().totalCloudCover(), tile.current().totalCloudCover(), delta);
    }

    public static ServerWeatherSnapshot interpolateSnapshot(ClientWeatherTile tile, long nowMillis) {
        float delta = temporalLerpFactor(tile.transitionStartedAtMillis(), nowMillis);
        ServerWeatherSnapshot previous = tile.previous();
        ServerWeatherSnapshot current = tile.current();
        ResolvedPrecipitation resolved = delta < 0.5F ? previous.resolvedPrecipitation() : current.resolvedPrecipitation();
        return new ServerWeatherSnapshot(
            current.observedAt(),
            current.latitude(),
            current.longitude(),
            current.weatherCode(),
            current.precipitation(),
            WeatherMath.lerp(previous.precipitationRateMmPerHour(), current.precipitationRateMmPerHour(), delta),
            WeatherMath.lerp(previous.rainRateMmPerHour(), current.rainRateMmPerHour(), delta),
            WeatherMath.lerp(previous.snowfallRateCmPerHour(), current.snowfallRateCmPerHour(), delta),
            WeatherMath.lerp(previous.temperatureCelsius(), current.temperatureCelsius(), delta),
            WeatherMath.lerp(previous.relativeHumidity(), current.relativeHumidity(), delta),
            WeatherMath.lerp(previous.totalCloudCover(), current.totalCloudCover(), delta),
            WeatherMath.lerp(previous.lowCloudCover(), current.lowCloudCover(), delta),
            WeatherMath.lerp(previous.midCloudCover(), current.midCloudCover(), delta),
            WeatherMath.lerp(previous.highCloudCover(), current.highCloudCover(), delta),
            WeatherMath.lerp(previous.visibilityMetres(), current.visibilityMetres(), delta),
            WeatherMath.lerp(previous.windSpeedKmh(), current.windSpeedKmh(), delta),
            WeatherMath.lerp(previous.windDirectionDegrees(), current.windDirectionDegrees(), delta),
            WeatherMath.lerp(previous.windGustKmh(), current.windGustKmh(), delta),
            WeatherMath.hysteresis(previous.resolvedPrecipitation(), resolved, current.temperatureCelsius(), 1.0F),
            current.gameplaySeverity(),
            WeatherMath.lerp(previous.thunderPotential(), current.thunderPotential(), delta),
            current.stale(),
            current.revision()
        );
    }
}
