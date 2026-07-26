package com.ukgeo.realtimelocalisedweather.weather.server;

import com.ukgeo.realtimelocalisedweather.weather.GameplaySeverity;
import com.ukgeo.realtimelocalisedweather.weather.MeteorologicalPrecipitation;
import com.ukgeo.realtimelocalisedweather.weather.ResolvedPrecipitation;
import com.ukgeo.realtimelocalisedweather.weather.ServerWeatherSnapshot;
import com.ukgeo.realtimelocalisedweather.weather.WeatherTileKey;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class WeatherOverrideManager {
    private volatile OverrideEntry globalOverride;
    private final Map<WeatherTileKey, OverrideEntry> tileOverrides = new ConcurrentHashMap<>();

    public void setGlobal(ResolvedPrecipitation precipitation, GameplaySeverity severity, long durationMillis) {
        globalOverride = new OverrideEntry(null, precipitation, severity, System.currentTimeMillis() + Math.max(1L, durationMillis));
    }

    public void setTile(WeatherTileKey key, ResolvedPrecipitation precipitation, GameplaySeverity severity, long durationMillis) {
        tileOverrides.put(key, new OverrideEntry(key, precipitation, severity, System.currentTimeMillis() + Math.max(1L, durationMillis)));
    }

    public void clear() {
        globalOverride = null;
        tileOverrides.clear();
    }

    public Optional<OverrideEntry> lookup(WeatherTileKey key, long now) {
        clearExpired(now);
        OverrideEntry specific = tileOverrides.get(key);
        if (specific != null) {
            return Optional.of(specific);
        }
        return Optional.ofNullable(globalOverride);
    }

    public void clearExpired(long now) {
        OverrideEntry currentGlobal = globalOverride;
        if (currentGlobal != null && currentGlobal.expiresAtMillis <= now) {
            globalOverride = null;
        }
        tileOverrides.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis <= now);
    }

    public record OverrideEntry(WeatherTileKey key, ResolvedPrecipitation precipitation, GameplaySeverity severity, long expiresAtMillis) {
        public ServerWeatherSnapshot toSnapshot(ServerWeatherSnapshot base, long revision) {
            float rate = switch (severity) {
                case TRACE -> 0.02F;
                case LIGHT -> 0.6F;
                case MODERATE -> 1.8F;
                case HEAVY -> 5.0F;
                case EXTREME -> 12.0F;
            };
            MeteorologicalPrecipitation meteorological = switch (precipitation) {
                case NONE -> MeteorologicalPrecipitation.NONE;
                case DRIZZLE -> MeteorologicalPrecipitation.DRIZZLE;
                case RAIN, THUNDER_RAIN -> MeteorologicalPrecipitation.RAIN;
                case SNOW, THUNDER_SNOW -> MeteorologicalPrecipitation.SNOW;
                case SLEET -> MeteorologicalPrecipitation.SNOW_SHOWERS;
                case FREEZING_RAIN -> MeteorologicalPrecipitation.FREEZING_RAIN;
                case HAIL -> MeteorologicalPrecipitation.HAIL;
            };
            return new ServerWeatherSnapshot(
                Instant.now(),
                base.latitude(),
                base.longitude(),
                precipitation.supportsThunder() ? 95 : 0,
                meteorological,
                rate,
                precipitation.isLiquid() ? rate : 0.0F,
                precipitation.isSnowy() ? rate : 0.0F,
                base.temperatureCelsius(),
                base.relativeHumidity(),
                base.visibilityMetres(),
                base.windSpeedKmh(),
                base.windDirectionDegrees(),
                base.windGustKmh(),
                precipitation,
                severity,
                precipitation.supportsThunder() ? 1.0F : 0.0F,
                false,
                revision
            );
        }
    }
}
