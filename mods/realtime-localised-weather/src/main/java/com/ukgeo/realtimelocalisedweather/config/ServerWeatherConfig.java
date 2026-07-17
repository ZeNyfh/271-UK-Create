package com.ukgeo.realtimelocalisedweather.config;

import com.ukgeo.realtimelocalisedweather.weather.ResolvedPrecipitation;
import com.ukgeo.realtimelocalisedweather.weather.SeasonalPrecipitationPolicy;
import com.ukgeo.realtimelocalisedweather.weather.WeatherAuthorityMode;
import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class ServerWeatherConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.EnumValue<WeatherAuthorityMode> AUTHORITY_MODE;
    public static final ModConfigSpec.ConfigValue<String> API_BASE_URL;
    public static final ModConfigSpec.ConfigValue<String> WEATHER_MODEL;
    public static final ModConfigSpec.IntValue REFRESH_INTERVAL_MINUTES;
    public static final ModConfigSpec.IntValue MINIMUM_FORCED_REFRESH_MINUTES;
    public static final ModConfigSpec.IntValue STALE_CACHE_HOURS;
    public static final ModConfigSpec.IntValue HARD_CACHE_EXPIRY_HOURS;
    public static final ModConfigSpec.IntValue MAXIMUM_CONCURRENT_REQUESTS;
    public static final ModConfigSpec.IntValue ZONE_SIZE_BLOCKS;
    public static final ModConfigSpec.IntValue ACTIVE_ZONE_RADIUS;
    public static final ModConfigSpec.IntValue PREFETCH_ZONE_RADIUS;
    public static final ModConfigSpec.IntValue INACTIVE_TILE_RETENTION_MINUTES;
    public static final ModConfigSpec.EnumValue<SeasonalPrecipitationPolicy> SEASONAL_PRECIPITATION_POLICY;
    public static final ModConfigSpec.EnumValue<ResolvedPrecipitation> EARLY_WINTER_LIQUID_RESULT;
    public static final ModConfigSpec.EnumValue<ResolvedPrecipitation> MID_WINTER_LIQUID_RESULT;
    public static final ModConfigSpec.EnumValue<ResolvedPrecipitation> LATE_WINTER_LIQUID_RESULT;
    public static final ModConfigSpec.EnumValue<ResolvedPrecipitation> WINTER_THUNDER_RESULT;
    public static final ModConfigSpec.EnumValue<ResolvedPrecipitation> WINTER_FREEZING_RAIN_RESULT;
    public static final ModConfigSpec.BooleanValue TROPICAL_WINTER_CONVERSION;
    public static final ModConfigSpec.BooleanValue ENABLE_GAMEPLAY_RAIN;
    public static final ModConfigSpec.BooleanValue ENABLE_SNOW_ACCUMULATION;
    public static final ModConfigSpec.BooleanValue ENABLE_ICE_FORMATION;
    public static final ModConfigSpec.BooleanValue ENABLE_CAULDRON_FILLING;
    public static final ModConfigSpec.BooleanValue ENABLE_FIRE_EXTINGUISHING;
    public static final ModConfigSpec.BooleanValue ENABLE_AUTHORITATIVE_LIGHTNING;
    public static final ModConfigSpec.ConfigValue<List<? extends Double>> GAMEPLAY_SEVERITY_THRESHOLDS;
    public static final ModConfigSpec.ConfigValue<String> WEATHER_COMMAND_BEHAVIOUR;
    public static final ModConfigSpec.BooleanValue REQUEST_BUDGET_SAFEGUARDS;
    public static final ModConfigSpec.DoubleValue SNOW_THRESHOLD_CELSIUS;
    public static final ModConfigSpec.DoubleValue SLEET_BAND_CELSIUS;
    public static final ModConfigSpec.DoubleValue WINTER_TEMPERATURE_BIAS_CELSIUS;
    public static final ModConfigSpec.DoubleValue SPRING_TEMPERATURE_BIAS_CELSIUS;
    public static final ModConfigSpec.DoubleValue SUMMER_TEMPERATURE_BIAS_CELSIUS;
    public static final ModConfigSpec.DoubleValue AUTUMN_TEMPERATURE_BIAS_CELSIUS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("realtime_localised_weather");
        ENABLED = builder.define("enabled", true);
        AUTHORITY_MODE = builder.defineEnum("authority_mode", WeatherAuthorityMode.LIVE);
        API_BASE_URL = builder.define("api_base_url", "https://api.open-meteo.com/v1/forecast");
        WEATHER_MODEL = builder.define("weather_model", "auto");
        REFRESH_INTERVAL_MINUTES = builder.defineInRange("refresh_interval_minutes", 15, 1, 240);
        MINIMUM_FORCED_REFRESH_MINUTES = builder.defineInRange("minimum_forced_refresh_minutes", 5, 1, 120);
        STALE_CACHE_HOURS = builder.defineInRange("stale_cache_hours", 2, 1, 240);
        HARD_CACHE_EXPIRY_HOURS = builder.defineInRange("hard_cache_expiry_hours", 24, 1, 24 * 30);
        MAXIMUM_CONCURRENT_REQUESTS = builder.defineInRange("maximum_concurrent_requests", 2, 1, 16);
        ZONE_SIZE_BLOCKS = builder.defineInRange("zone_size_blocks", 256, 32, 2048);
        ACTIVE_ZONE_RADIUS = builder.defineInRange("active_zone_radius", 2, 0, 12);
        PREFETCH_ZONE_RADIUS = builder.defineInRange("prefetch_zone_radius", 1, 0, 8);
        INACTIVE_TILE_RETENTION_MINUTES = builder.defineInRange("inactive_tile_retention_minutes", 30, 1, 24 * 60);
        SEASONAL_PRECIPITATION_POLICY = builder.defineEnum("seasonal_precipitation_policy", SeasonalPrecipitationPolicy.SERENE_SEASONS_WINTER_SNOW);
        EARLY_WINTER_LIQUID_RESULT = builder.defineEnum("early_winter_liquid_result", ResolvedPrecipitation.SNOW);
        MID_WINTER_LIQUID_RESULT = builder.defineEnum("mid_winter_liquid_result", ResolvedPrecipitation.SNOW);
        LATE_WINTER_LIQUID_RESULT = builder.defineEnum("late_winter_liquid_result", ResolvedPrecipitation.SNOW);
        WINTER_THUNDER_RESULT = builder.defineEnum("winter_thunder_result", ResolvedPrecipitation.THUNDER_SNOW);
        WINTER_FREEZING_RAIN_RESULT = builder.defineEnum("winter_freezing_rain_result", ResolvedPrecipitation.SNOW);
        TROPICAL_WINTER_CONVERSION = builder.define("tropical_winter_conversion", false);
        ENABLE_GAMEPLAY_RAIN = builder.define("enable_gameplay_rain", true);
        ENABLE_SNOW_ACCUMULATION = builder.define("enable_snow_accumulation", true);
        ENABLE_ICE_FORMATION = builder.define("enable_ice_formation", true);
        ENABLE_CAULDRON_FILLING = builder.define("enable_cauldron_filling", true);
        ENABLE_FIRE_EXTINGUISHING = builder.define("enable_fire_extinguishing", true);
        ENABLE_AUTHORITATIVE_LIGHTNING = builder.define("enable_authoritative_lightning", true);
        GAMEPLAY_SEVERITY_THRESHOLDS = builder.defineListAllowEmpty(
            "gameplay_severity_thresholds",
            List.of(0.05D, 0.5D, 2.0D, 8.0D),
            entry -> entry instanceof Number
        );
        WEATHER_COMMAND_BEHAVIOUR = builder.define("weather_command_behaviour", "redirect");
        REQUEST_BUDGET_SAFEGUARDS = builder.define("request_budget_safeguards", true);
        SNOW_THRESHOLD_CELSIUS = builder.defineInRange("snow_threshold_celsius", 1.0D, -20.0D, 10.0D);
        SLEET_BAND_CELSIUS = builder.defineInRange("sleet_band_celsius", 1.5D, 0.0D, 10.0D);
        WINTER_TEMPERATURE_BIAS_CELSIUS = builder.defineInRange("winter_temperature_bias_celsius", -5.0D, -20.0D, 10.0D);
        SPRING_TEMPERATURE_BIAS_CELSIUS = builder.defineInRange("spring_temperature_bias_celsius", -1.0D, -20.0D, 10.0D);
        SUMMER_TEMPERATURE_BIAS_CELSIUS = builder.defineInRange("summer_temperature_bias_celsius", 2.0D, -20.0D, 10.0D);
        AUTUMN_TEMPERATURE_BIAS_CELSIUS = builder.defineInRange("autumn_temperature_bias_celsius", -1.0D, -20.0D, 10.0D);
        builder.pop();
        SPEC = builder.build();
    }

    private ServerWeatherConfig() {
    }

    public static double[] severityThresholds() {
        List<? extends Double> values = safeList(GAMEPLAY_SEVERITY_THRESHOLDS, List.of(0.05D, 0.5D, 2.0D, 8.0D));
        double[] defaults = {0.05D, 0.5D, 2.0D, 8.0D};
        if (values == null || values.size() < 4) {
            return defaults;
        }
        double[] result = new double[4];
        for (int i = 0; i < 4; i++) {
            result[i] = Math.max(0.0D, values.get(i));
        }
        return result;
    }

    public static <T extends Enum<T>> T safeEnum(ModConfigSpec.EnumValue<T> value, T fallback) {
        try {
            return value.get();
        } catch (IllegalStateException exception) {
            return fallback;
        }
    }

    public static boolean safeBoolean(ModConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return value.get();
        } catch (IllegalStateException exception) {
            return fallback;
        }
    }

    public static int safeInt(ModConfigSpec.IntValue value, int fallback) {
        try {
            return value.get();
        } catch (IllegalStateException exception) {
            return fallback;
        }
    }

    public static double safeDouble(ModConfigSpec.DoubleValue value, double fallback) {
        try {
            return value.get();
        } catch (IllegalStateException exception) {
            return fallback;
        }
    }

    public static List<? extends Double> safeList(ModConfigSpec.ConfigValue<List<? extends Double>> value, List<? extends Double> fallback) {
        try {
            return value.get();
        } catch (IllegalStateException exception) {
            return fallback;
        }
    }

    public static String safeString(ModConfigSpec.ConfigValue<String> value, String fallback) {
        try {
            return value.get();
        } catch (IllegalStateException exception) {
            return fallback;
        }
    }
}
