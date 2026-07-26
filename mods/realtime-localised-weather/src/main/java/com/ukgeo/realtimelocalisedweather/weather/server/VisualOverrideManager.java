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
        visualTestEntry = new Entry(percent, revisions.incrementAndGet());
    }

    Optional<Entry> lookup(com.ukgeo.realtimelocalisedweather.weather.WeatherTileKey key) {
        return Optional.ofNullable(visualTestEntry);
    }

    record Entry(Integer rainPercent, long revision) {
        ServerWeatherSnapshot apply(ServerWeatherSnapshot base) {
            // The client maps live millimetres/hour logarithmically to vanilla's rain gradient.
            // Invert that mapping here so /rain 50 is a genuine 50% visual test rather than
            // almost-full rain caused by feeding it 6 mm/h.
            float rainRate = rainPercent == null
                ? base.precipitationRateMmPerHour()
                : (float) Math.expm1(Math.log1p(12.0D) * rainPercent / 100.0D);
            boolean raining = rainPercent != null && rainPercent > 0;
            ResolvedPrecipitation precipitation = rainPercent == null ? base.resolvedPrecipitation() : (raining ? ResolvedPrecipitation.RAIN : ResolvedPrecipitation.NONE);
            MeteorologicalPrecipitation meteorological = rainPercent == null ? base.precipitation() : (raining ? MeteorologicalPrecipitation.RAIN : MeteorologicalPrecipitation.NONE);
            return new ServerWeatherSnapshot(
                base.observedAt(), base.latitude(), base.longitude(), base.weatherCode(), meteorological,
                rainRate, raining ? rainRate : 0.0F, 0.0F, base.temperatureCelsius(), base.relativeHumidity(),
                base.visibilityMetres(), base.windSpeedKmh(), base.windDirectionDegrees(), base.windGustKmh(),
                precipitation, raining ? GameplaySeverity.MODERATE : GameplaySeverity.TRACE, raining ? base.thunderPotential() : 0.0F,
                base.stale(), Math.max(base.revision() + 1L, revision)
            );
        }
    }
}
