package com.ukgeo.realtimelocalisedweather;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ukgeo.realtimelocalisedweather.weather.client.VisualWeatherMath;
import org.junit.jupiter.api.Test;

class VisualWeatherMathTest {
    @Test
    void rainRateUsesVisibleMeteorologicalBands() {
        assertEquals(0.11F, VisualWeatherMath.precipitationRateToRainLevel(0.1F, 1.0F), 0.0001F);
        assertEquals(0.45F, VisualWeatherMath.precipitationRateToRainLevel(2.0F, 1.0F), 0.0001F);
        assertEquals(0.70F, VisualWeatherMath.precipitationRateToRainLevel(4.0F, 1.0F), 0.0001F);
        assertEquals(1.0F, VisualWeatherMath.precipitationRateToRainLevel(8.0F, 1.0F), 0.0001F);
        assertEquals(1.0F, VisualWeatherMath.precipitationRateToRainLevel(20.0F, 1.0F), 0.0001F);
    }
}
