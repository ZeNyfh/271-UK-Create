package com.ukgeo.realtimelocalisedweather.weather;

import com.ukgeo.realtimelocalisedweather.config.ServerWeatherConfig;

public final class WeatherSeverityMapper {
    private WeatherSeverityMapper() {
    }

    public static GameplaySeverity fromRates(float precipitationRateMmPerHour, float snowfallRateCmPerHour, float thunderPotential) {
        double effectiveRate = Math.max(precipitationRateMmPerHour, snowfallRateCmPerHour * 10.0D);
        double[] thresholds = ServerWeatherConfig.severityThresholds();
        GameplaySeverity severity;
        if (effectiveRate < thresholds[0]) {
            severity = GameplaySeverity.TRACE;
        } else if (effectiveRate < thresholds[1]) {
            severity = GameplaySeverity.LIGHT;
        } else if (effectiveRate < thresholds[2]) {
            severity = GameplaySeverity.MODERATE;
        } else if (effectiveRate < thresholds[3]) {
            severity = GameplaySeverity.HEAVY;
        } else {
            severity = GameplaySeverity.EXTREME;
        }
        if (thunderPotential >= 0.75F && severity.ordinal() < GameplaySeverity.HEAVY.ordinal()) {
            return GameplaySeverity.HEAVY;
        }
        return severity;
    }

    public static float toGameplayMultiplier(GameplaySeverity severity) {
        return switch (severity) {
            case TRACE -> 0.1F;
            case LIGHT -> 0.6F;
            case MODERATE -> 1.0F;
            case HEAVY -> 1.4F;
            case EXTREME -> 1.8F;
        };
    }
}
