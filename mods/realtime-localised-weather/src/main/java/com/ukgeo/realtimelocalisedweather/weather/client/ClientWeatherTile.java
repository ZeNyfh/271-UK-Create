package com.ukgeo.realtimelocalisedweather.weather.client;

import com.ukgeo.realtimelocalisedweather.weather.ServerWeatherSnapshot;
import com.ukgeo.realtimelocalisedweather.weather.WeatherTileKey;

public record ClientWeatherTile(
    WeatherTileKey key,
    ServerWeatherSnapshot previous,
    ServerWeatherSnapshot current,
    long transitionStartedAtMillis,
    long visualSeed
) {
}
