package com.ukgeo.realtimelocalisedweather.compat.sereneseasons;

import com.ukgeo.realtimelocalisedweather.config.ServerWeatherConfig;
import com.ukgeo.realtimelocalisedweather.weather.MeteorologicalPrecipitation;
import com.ukgeo.realtimelocalisedweather.weather.ResolvedPrecipitation;
import com.ukgeo.realtimelocalisedweather.weather.SeasonalPrecipitationPolicy;

public final class SerenePrecipitationResolver {
    /** Open-Meteo snowfall (cm/h) above this forces snowy resolved type. */
    public static final float OPEN_METEO_SNOWFALL_FORCE_CM_PER_HOUR = 0.05F;

    private SerenePrecipitationResolver() {
    }

    public static Resolution resolve(MeteorologicalPrecipitation precipitation, float temperatureCelsius, SereneSeasonSnapshot seasonSnapshot) {
        return resolve(precipitation, temperatureCelsius, 0.0F, seasonSnapshot);
    }

    public static Resolution resolve(
        MeteorologicalPrecipitation precipitation,
        float temperatureCelsius,
        float snowfallRateCmPerHour,
        SereneSeasonSnapshot seasonSnapshot
    ) {
        ResolvedPrecipitation direct = direct(precipitation);
        Resolution base;
        if (direct == ResolvedPrecipitation.NONE || !seasonSnapshot.detected()) {
            base = new Resolution(direct, false);
        } else {
            SeasonalPrecipitationPolicy policy = ServerWeatherConfig.safeEnum(ServerWeatherConfig.SEASONAL_PRECIPITATION_POLICY, SeasonalPrecipitationPolicy.SERENE_SEASONS_WINTER_SNOW);
            if (policy == SeasonalPrecipitationPolicy.REAL_DATA_ONLY) {
                base = new Resolution(direct, false);
            } else if (seasonSnapshot.tropicalBiome() && !ServerWeatherConfig.safeBoolean(ServerWeatherConfig.TROPICAL_WINTER_CONVERSION, false)) {
                base = new Resolution(direct, false);
            } else if (policy == SeasonalPrecipitationPolicy.SERENE_SEASONS_WINTER_SNOW && seasonSnapshot.winter()) {
                base = new Resolution(winterResult(precipitation), true);
            } else if (policy == SeasonalPrecipitationPolicy.TEMPERATURE_AND_SEASON) {
                base = new Resolution(temperatureResult(precipitation, temperatureCelsius, seasonSnapshot), false);
            } else {
                base = new Resolution(direct, false);
            }
        }
        return preferOpenMeteoSnowfall(precipitation, snowfallRateCmPerHour, base);
    }

    /**
     * Explicit Open-Meteo snowfall remains snow even outside winter / after season policy.
     * Winter rain→snow conversion still applies when snowfall rate is zero.
     */
    private static Resolution preferOpenMeteoSnowfall(
        MeteorologicalPrecipitation precipitation,
        float snowfallRateCmPerHour,
        Resolution base
    ) {
        if (snowfallRateCmPerHour <= OPEN_METEO_SNOWFALL_FORCE_CM_PER_HOUR) {
            return base;
        }
        ResolvedPrecipitation resolved = base.resolvedPrecipitation();
        boolean thunder = precipitation == MeteorologicalPrecipitation.THUNDERSTORM
            || resolved == ResolvedPrecipitation.THUNDER_RAIN
            || resolved == ResolvedPrecipitation.THUNDER_SNOW;
        ResolvedPrecipitation forced = thunder
            ? ResolvedPrecipitation.THUNDER_SNOW
            : (resolved.isSnowy() ? resolved : ResolvedPrecipitation.SNOW);
        if (forced == resolved) {
            return base;
        }
        return new Resolution(forced, base.seasonConversionApplied());
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
