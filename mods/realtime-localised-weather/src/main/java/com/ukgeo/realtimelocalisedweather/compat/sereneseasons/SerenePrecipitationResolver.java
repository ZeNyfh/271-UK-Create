package com.ukgeo.realtimelocalisedweather.compat.sereneseasons;

import com.ukgeo.realtimelocalisedweather.config.ServerWeatherConfig;
import com.ukgeo.realtimelocalisedweather.weather.MeteorologicalPrecipitation;
import com.ukgeo.realtimelocalisedweather.weather.ResolvedPrecipitation;
import com.ukgeo.realtimelocalisedweather.weather.SeasonalPrecipitationPolicy;

public final class SerenePrecipitationResolver {
    private SerenePrecipitationResolver() {
    }

    public static Resolution resolve(MeteorologicalPrecipitation precipitation, float temperatureCelsius, SereneSeasonSnapshot seasonSnapshot) {
        ResolvedPrecipitation direct = direct(precipitation);
        if (direct == ResolvedPrecipitation.NONE || !seasonSnapshot.detected()) {
            return new Resolution(direct, false);
        }
        SeasonalPrecipitationPolicy policy = ServerWeatherConfig.safeEnum(ServerWeatherConfig.SEASONAL_PRECIPITATION_POLICY, SeasonalPrecipitationPolicy.SERENE_SEASONS_WINTER_SNOW);
        if (policy == SeasonalPrecipitationPolicy.REAL_DATA_ONLY) {
            return new Resolution(direct, false);
        }
        if (seasonSnapshot.tropicalBiome() && !ServerWeatherConfig.safeBoolean(ServerWeatherConfig.TROPICAL_WINTER_CONVERSION, false)) {
            return new Resolution(direct, false);
        }
        if (policy == SeasonalPrecipitationPolicy.SERENE_SEASONS_WINTER_SNOW && seasonSnapshot.winter()) {
            return new Resolution(winterResult(precipitation), true);
        }
        if (policy == SeasonalPrecipitationPolicy.TEMPERATURE_AND_SEASON) {
            return new Resolution(temperatureResult(precipitation, temperatureCelsius, seasonSnapshot), false);
        }
        return new Resolution(direct, false);
    }

    private static ResolvedPrecipitation direct(MeteorologicalPrecipitation precipitation) {
        return switch (precipitation) {
            case NONE -> ResolvedPrecipitation.NONE;
            case DRIZZLE -> ResolvedPrecipitation.DRIZZLE;
            case RAIN, SHOWERS -> ResolvedPrecipitation.RAIN;
            case SNOW, SNOW_SHOWERS -> ResolvedPrecipitation.SNOW;
            case FREEZING_DRIZZLE, FREEZING_RAIN -> ResolvedPrecipitation.FREEZING_RAIN;
            case HAIL -> ResolvedPrecipitation.HAIL;
            case THUNDERSTORM -> ResolvedPrecipitation.THUNDER_RAIN;
        };
    }

    private static ResolvedPrecipitation winterResult(MeteorologicalPrecipitation precipitation) {
        return switch (precipitation) {
            case NONE -> ResolvedPrecipitation.NONE;
            case SNOW, SNOW_SHOWERS -> ResolvedPrecipitation.SNOW;
            case HAIL -> ResolvedPrecipitation.HAIL;
            case THUNDERSTORM -> ServerWeatherConfig.safeEnum(ServerWeatherConfig.WINTER_THUNDER_RESULT, ResolvedPrecipitation.THUNDER_SNOW);
            case FREEZING_DRIZZLE, FREEZING_RAIN -> ServerWeatherConfig.safeEnum(ServerWeatherConfig.WINTER_FREEZING_RAIN_RESULT, ResolvedPrecipitation.SNOW);
            case DRIZZLE, RAIN, SHOWERS -> resolveWinterLiquid(
                ServerWeatherConfig.safeEnum(ServerWeatherConfig.MID_WINTER_LIQUID_RESULT, ResolvedPrecipitation.SNOW),
                ServerWeatherConfig.safeEnum(ServerWeatherConfig.EARLY_WINTER_LIQUID_RESULT, ResolvedPrecipitation.SNOW),
                ServerWeatherConfig.safeEnum(ServerWeatherConfig.LATE_WINTER_LIQUID_RESULT, ResolvedPrecipitation.SNOW)
            );
        };
    }

    private static ResolvedPrecipitation resolveWinterLiquid(ResolvedPrecipitation mid, ResolvedPrecipitation early, ResolvedPrecipitation late) {
        if (mid != null) {
            return mid;
        }
        if (early != null) {
            return early;
        }
        return late == null ? ResolvedPrecipitation.SNOW : late;
    }

    private static ResolvedPrecipitation temperatureResult(MeteorologicalPrecipitation precipitation, float temperatureCelsius, SereneSeasonSnapshot seasonSnapshot) {
        ResolvedPrecipitation direct = direct(precipitation);
        if (!precipitation.isLiquid() || direct == ResolvedPrecipitation.THUNDER_RAIN) {
            return direct;
        }
        double bias = seasonalBias(seasonSnapshot);
        double effectiveTemperature = temperatureCelsius + bias;
        double snowThreshold = ServerWeatherConfig.safeDouble(ServerWeatherConfig.SNOW_THRESHOLD_CELSIUS, 1.0D);
        double sleetBand = ServerWeatherConfig.safeDouble(ServerWeatherConfig.SLEET_BAND_CELSIUS, 1.5D);
        if (effectiveTemperature <= snowThreshold) {
            return ResolvedPrecipitation.SNOW;
        }
        if (effectiveTemperature <= snowThreshold + sleetBand) {
            return ResolvedPrecipitation.SLEET;
        }
        return direct;
    }

    private static double seasonalBias(SereneSeasonSnapshot snapshot) {
        String subSeason = snapshot.subSeason();
        if (subSeason.contains("WINTER")) {
            return ServerWeatherConfig.safeDouble(ServerWeatherConfig.WINTER_TEMPERATURE_BIAS_CELSIUS, -5.0D);
        }
        if (subSeason.contains("SPRING")) {
            return ServerWeatherConfig.safeDouble(ServerWeatherConfig.SPRING_TEMPERATURE_BIAS_CELSIUS, -1.0D);
        }
        if (subSeason.contains("SUMMER")) {
            return ServerWeatherConfig.safeDouble(ServerWeatherConfig.SUMMER_TEMPERATURE_BIAS_CELSIUS, 2.0D);
        }
        if (subSeason.contains("AUTUMN") || subSeason.contains("FALL")) {
            return ServerWeatherConfig.safeDouble(ServerWeatherConfig.AUTUMN_TEMPERATURE_BIAS_CELSIUS, -1.0D);
        }
        return 0.0D;
    }

    public record Resolution(ResolvedPrecipitation resolvedPrecipitation, boolean seasonConversionApplied) {
    }
}
