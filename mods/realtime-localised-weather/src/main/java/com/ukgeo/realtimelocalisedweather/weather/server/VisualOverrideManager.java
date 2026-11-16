package com.ukgeo.realtimelocalisedweather.weather.server;

import com.ukgeo.realtimelocalisedweather.weather.GameplaySeverity;
import com.ukgeo.realtimelocalisedweather.weather.MeteorologicalPrecipitation;
import com.ukgeo.realtimelocalisedweather.weather.ResolvedPrecipitation;
import com.ukgeo.realtimelocalisedweather.weather.ServerWeatherSnapshot;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Runtime-only visual test overrides.  They deliberately do not alter gameplay authority. */
final class VisualOverrideManager {
    private final AtomicLong revisions = new AtomicLong(1_000_000L);
    private volatile Entry visualTestEntry;

    void setRain(com.ukgeo.realtimelocalisedweather.weather.WeatherTileKey key, int percent) {
        visualTestEntry = new Entry(percent, false, revisions.incrementAndGet());
    }

    void setSnow(com.ukgeo.realtimelocalisedweather.weather.WeatherTileKey key, int percent) {
        visualTestEntry = new Entry(percent, true, revisions.incrementAndGet());
    }

    Optional<Entry> lookup(com.ukgeo.realtimelocalisedweather.weather.WeatherTileKey key) {
        return Optional.ofNullable(visualTestEntry);
    }

    record Entry(Integer percent, boolean snow, long revision) {
        ServerWeatherSnapshot apply(ServerWeatherSnapshot base) {
            if (percent == null) {
                return base;
            }
            if (percent == 0) {
                return new ServerWeatherSnapshot(
                    base.observedAt(), base.latitude(), base.longitude(), base.weatherCode(),
                    MeteorologicalPrecipitation.NONE,
                    0.0F, 0.0F, 0.0F, base.temperatureCelsius(), base.relativeHumidity(),
                    base.visibilityMetres(), base.windSpeedKmh(), base.windDirectionDegrees(), base.windGustKmh(),
                    ResolvedPrecipitation.NONE, GameplaySeverity.TRACE, 0.0F,
                    base.stale(), Math.max(base.revision() + 1L, revision)
                );
            }
            // Invert client logarithmic rain mapping so /rain|/snow N is a genuine N% visual test.
            float liquidEquivalent = (float) Math.expm1(Math.log1p(12.0D) * percent / 100.0D);
            if (snow) {
                return new ServerWeatherSnapshot(
                    base.observedAt(), base.latitude(), base.longitude(), base.weatherCode(),
                    MeteorologicalPrecipitation.SNOW,
                    liquidEquivalent, 0.0F, liquidEquivalent / 10.0F, base.temperatureCelsius(), base.relativeHumidity(),
                    base.visibilityMetres(), base.windSpeedKmh(), base.windDirectionDegrees(), base.windGustKmh(),
                    ResolvedPrecipitation.SNOW, GameplaySeverity.MODERATE, 0.0F,
                    base.stale(), Math.max(base.revision() + 1L, revision)
                );
            }
            return new ServerWeatherSnapshot(
                base.observedAt(), base.latitude(), base.longitude(), base.weatherCode(),
                MeteorologicalPrecipitation.RAIN,
                liquidEquivalent, liquidEquivalent, 0.0F, base.temperatureCelsius(), base.relativeHumidity(),
                base.visibilityMetres(), base.windSpeedKmh(), base.windDirectionDegrees(), base.windGustKmh(),
                ResolvedPrecipitation.RAIN, GameplaySeverity.MODERATE, base.thunderPotential(),
                base.stale(), Math.max(base.revision() + 1L, revision)
            );
        }
    }
}
