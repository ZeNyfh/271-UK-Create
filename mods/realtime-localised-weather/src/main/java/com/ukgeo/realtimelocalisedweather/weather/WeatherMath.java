package com.ukgeo.realtimelocalisedweather.weather;

public final class WeatherMath {
    private WeatherMath() {
    }

    public static float lerp(float start, float end, float delta) {
        return start + (end - start) * delta;
    }

    public static double lerp(double start, double end, double delta) {
        return start + (end - start) * delta;
    }

    public static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    public static float smoothstep(float value) {
        float x = clamp01(value);
        return x * x * (3.0F - 2.0F * x);
    }

    public static float bilinear(float q00, float q10, float q01, float q11, float xLerp, float zLerp) {
        float a = lerp(q00, q10, xLerp);
        float b = lerp(q01, q11, xLerp);
        return lerp(a, b, zLerp);
    }

    public static ResolvedPrecipitation hysteresis(ResolvedPrecipitation previous, ResolvedPrecipitation candidate, float temperatureCelsius, float band) {
        if (previous == candidate) {
            return candidate;
        }
        // Never block a transition TO snowy types from the server/Open-Meteo.
        // UK snow often arrives at 0–2°C after rain; the old RAIN→SNOW gate hid it.
        if (candidate.isSnowy()) {
            return candidate;
        }
        // Briefly keep snow when flapping to liquid near freezing.
        if (previous.isSnowy() && candidate.isLiquid() && temperatureCelsius < band) {
            return previous;
        }
        return candidate;
    }
}
