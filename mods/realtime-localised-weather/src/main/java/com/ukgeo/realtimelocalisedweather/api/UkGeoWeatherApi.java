package com.ukgeo.realtimelocalisedweather.api;

import com.ukgeo.realtimelocalisedweather.RealtimeLocalisedWeatherMod;

public final class UkGeoWeatherApi {
    private UkGeoWeatherApi() {
    }

    public static RegionalWeatherAccess regionalWeather() {
        return RealtimeLocalisedWeatherMod.serverWeatherManager();
    }
}
