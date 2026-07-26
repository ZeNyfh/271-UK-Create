package com.ukgeo.realtimelocalisedweather.config;

import java.util.Locale;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class ClientWeatherConfig {
    public enum PrecipitationRendererMode {
        CUSTOM,
        VANILLA_FALLBACK,
        OFF
    }

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.EnumValue<PrecipitationRendererMode> PRECIPITATION_RENDERER;
    public static final ModConfigSpec.DoubleValue TRANSITION_SECONDS;
    public static final ModConfigSpec.IntValue PRECIPITATION_RENDER_DISTANCE_BLOCKS;
    public static final ModConfigSpec.DoubleValue PRECIPITATION_DENSITY_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue SNOW_DENSITY_MULTIPLIER;
    public static final ModConfigSpec.IntValue WEATHER_POLL_INTERVAL_TICKS;
    public static final ModConfigSpec.BooleanValue ENABLE_WEATHER_FOG;
    public static final ModConfigSpec.BooleanValue ENABLE_WEATHER_SOUNDS;
    public static final ModConfigSpec.BooleanValue ENABLE_WIND_SLANT;
    public static final ModConfigSpec.BooleanValue ENABLE_SPLASHES;
    public static final ModConfigSpec.BooleanValue ENABLE_COSMETIC_DISTANT_LIGHTNING;
    public static final ModConfigSpec.BooleanValue ENABLE_DEBUG_OVERLAY;
    public static final ModConfigSpec.ConfigValue<String> GRAPHICS_FALLBACK_MODE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("realtime_localised_weather");
        ENABLED = builder.define("enabled", true);
        PRECIPITATION_RENDERER = builder.defineEnum("precipitation_renderer", PrecipitationRendererMode.CUSTOM);
        TRANSITION_SECONDS = builder.defineInRange("transition_seconds", 24.0D, 1.0D, 120.0D);
        PRECIPITATION_RENDER_DISTANCE_BLOCKS = builder.defineInRange("precipitation_render_distance_blocks", 128, 16, 512);
        PRECIPITATION_DENSITY_MULTIPLIER = builder.defineInRange("precipitation_density_multiplier", 1.0D, 0.0D, 4.0D);
        SNOW_DENSITY_MULTIPLIER = builder.defineInRange("snow_density_multiplier", 1.0D, 0.0D, 4.0D);
        WEATHER_POLL_INTERVAL_TICKS = builder.defineInRange("weather_poll_interval_ticks", 40, 20, 1200);
        ENABLE_WEATHER_FOG = builder.define("enable_weather_fog", true);
        ENABLE_WEATHER_SOUNDS = builder.define("enable_weather_sounds", true);
        ENABLE_WIND_SLANT = builder.define("enable_wind_slant", true);
        ENABLE_SPLASHES = builder.define("enable_splashes", true);
        ENABLE_COSMETIC_DISTANT_LIGHTNING = builder.define("enable_cosmetic_distant_lightning", true);
        ENABLE_DEBUG_OVERLAY = builder.define("enable_debug_overlay", false);
        GRAPHICS_FALLBACK_MODE = builder.define("graphics_fallback_mode", "auto");
        builder.pop();
        SPEC = builder.build();
    }

    private ClientWeatherConfig() {
    }

    public static String graphicsFallbackMode() {
        return GRAPHICS_FALLBACK_MODE.get().toLowerCase(Locale.ROOT).trim();
    }

    public static double transitionSeconds() {
        try {
            return TRANSITION_SECONDS.get();
        } catch (IllegalStateException exception) {
            return 24.0D;
        }
    }

    public static int weatherPollIntervalTicks() {
        try {
            return WEATHER_POLL_INTERVAL_TICKS.get();
        } catch (IllegalStateException exception) {
            return 40;
        }
    }
}
