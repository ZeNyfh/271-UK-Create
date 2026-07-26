package com.ukgeo.realtimelocalisedweather.weather.client;

import com.ukgeo.realtimelocalisedweather.weather.WeatherMath;

/** Pure visual mappings calibrated to meteorological rain-rate bands. */
public final class VisualWeatherMath {
    private static final float[] RAIN_RATE_ANCHORS = {0.0F, 0.1F, 2.0F, 4.0F, 8.0F};
    // 0.1 mm/h must be visible, then light/moderate/heavy rain step up progressively.
    private static final float[] RAIN_LEVEL_ANCHORS = {0.0F, 0.11F, 0.45F, 0.70F, 1.0F};

    private VisualWeatherMath() {
    }

    public static float precipitationRateToRainLevel(float millimetresPerHour, float densityMultiplier) {
        if (!Float.isFinite(millimetresPerHour) || millimetresPerHour <= 0.0F) return 0.0F;
        return WeatherMath.clamp01(interpolateRainLevel(millimetresPerHour) * Math.max(0.0F, densityMultiplier));
    }

    private static float interpolateRainLevel(float rate) {
        if (rate >= RAIN_RATE_ANCHORS[RAIN_RATE_ANCHORS.length - 1]) return 1.0F;
        for (int index = 1; index < RAIN_RATE_ANCHORS.length; index++) {
            float upperRate = RAIN_RATE_ANCHORS[index];
            if (rate <= upperRate) {
                float lowerRate = RAIN_RATE_ANCHORS[index - 1];
                float progress = (rate - lowerRate) / (upperRate - lowerRate);
                return WeatherMath.lerp(RAIN_LEVEL_ANCHORS[index - 1], RAIN_LEVEL_ANCHORS[index], progress);
            }
        }
        return 1.0F;
    }
}
