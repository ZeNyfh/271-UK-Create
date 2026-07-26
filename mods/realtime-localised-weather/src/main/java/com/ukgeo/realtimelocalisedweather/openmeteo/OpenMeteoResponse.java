package com.ukgeo.realtimelocalisedweather.openmeteo;

import com.ukgeo.realtimelocalisedweather.weather.MeteorologicalPrecipitation;
import java.time.Instant;
import java.util.List;

public record OpenMeteoResponse(List<LocationWeather> locations) {
    public record LocationWeather(
        double latitude,
        double longitude,
        Instant observedAt,
        int weatherCode,
        float precipitation,
        float rain,
        float showers,
        float snowfall,
        float visibility,
        float temperature,
        float humidity,
        float windSpeed,
        float windDirection,
        float windGusts
    ) {
        public MeteorologicalPrecipitation meteorologicalPrecipitation() {
            return OpenMeteoParser.mapWeatherCode(weatherCode);
        }
    }

    public record LocationRequest(String requestId, double latitude, double longitude) {
    }
}
