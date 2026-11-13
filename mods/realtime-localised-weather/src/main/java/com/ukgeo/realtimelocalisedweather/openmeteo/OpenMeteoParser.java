package com.ukgeo.realtimelocalisedweather.openmeteo;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ukgeo.realtimelocalisedweather.weather.MeteorologicalPrecipitation;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

public final class OpenMeteoParser {
    private OpenMeteoParser() {
    }

    public static OpenMeteoResponse parse(String json) {
        JsonElement root = JsonParser.parseString(json);
        List<OpenMeteoResponse.LocationWeather> result = new ArrayList<>();
        if (root.isJsonArray()) {
            JsonArray array = root.getAsJsonArray();
            for (JsonElement element : array) {
                result.add(parseLocation(element.getAsJsonObject()));
            }
        } else if (root.isJsonObject()) {
            result.add(parseLocation(root.getAsJsonObject()));
        } else {
            throw new IllegalArgumentException("Malformed Open-Meteo payload");
        }
        return new OpenMeteoResponse(List.copyOf(result));
    }

    private static OpenMeteoResponse.LocationWeather parseLocation(JsonObject root) {
        JsonObject current = requireObject(root, "current");
        return new OpenMeteoResponse.LocationWeather(
            number(root, "latitude"),
            number(root, "longitude"),
            parseOpenMeteoTime(requireString(current, "time")),
            (int) number(current, "weather_code"),
            (float) number(current, "precipitation"),
            (float) number(current, "rain"),
            (float) number(current, "showers"),
            (float) number(current, "snowfall"),
            (float) number(current, "visibility"),
            (float) number(current, "temperature_2m"),
            (float) number(current, "relative_humidity_2m"),
            (float) number(current, "wind_speed_10m"),
            (float) number(current, "wind_direction_10m"),
            (float) number(current, "wind_gusts_10m")
        );
    }

    private static JsonObject requireObject(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonObject()) {
            throw new IllegalArgumentException("Missing object field " + key);
        }
        return object.getAsJsonObject(key);
    }

    private static String requireString(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            throw new IllegalArgumentException("Missing string field " + key);
        }
        return object.get(key).getAsString();
    }

    private static Instant parseOpenMeteoTime(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException("Malformed Open-Meteo time " + value, exception);
            }
        }
    }

    private static double number(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            throw new IllegalArgumentException("Missing numeric field " + key);
        }
        double value = object.get(key).getAsDouble();
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Non-finite numeric field " + key);
        }
        return value;
    }

    public static MeteorologicalPrecipitation mapWeatherCode(int weatherCode) {
        return switch (weatherCode) {
            case 51, 53, 55 -> MeteorologicalPrecipitation.DRIZZLE;
            case 56, 57 -> MeteorologicalPrecipitation.FREEZING_DRIZZLE;
            case 61, 63, 65 -> MeteorologicalPrecipitation.RAIN;
            case 66, 67 -> MeteorologicalPrecipitation.FREEZING_RAIN;
            case 71, 73, 75, 77 -> MeteorologicalPrecipitation.SNOW;
            case 80, 81, 82 -> MeteorologicalPrecipitation.SHOWERS;
            case 85, 86 -> MeteorologicalPrecipitation.SNOW_SHOWERS;
            case 95 -> MeteorologicalPrecipitation.THUNDERSTORM;
            case 96, 99 -> MeteorologicalPrecipitation.HAIL;
            default -> weatherCode == 45 || weatherCode == 48 || weatherCode == 0 || weatherCode == 1 || weatherCode == 2 || weatherCode == 3
                ? MeteorologicalPrecipitation.NONE
                : MeteorologicalPrecipitation.NONE;
        };
    }

    /**
     * Classify precipitation using Open-Meteo rate fields when present, falling back to WMO
     * {@code weather_code}. Snowfall is cm/h; rain/showers/precipitation are mm/h — treated as
     * roughly comparable for dominance checks.
     */
    public static MeteorologicalPrecipitation classify(
        int weatherCode,
        float precipitation,
        float rain,
        float showers,
        float snowfall
    ) {
        MeteorologicalPrecipitation fromCode = mapWeatherCode(weatherCode);

        // Thunder/hail stay code-driven; Serene winter policy can turn THUNDERSTORM into THUNDER_SNOW.
        if (weatherCode == 95 || weatherCode == 96 || weatherCode == 99) {
            return fromCode;
        }

        float liquid = Math.max(0.0F, rain) + Math.max(0.0F, showers);
        boolean snowSignificant = snowfall > 0.05F;
        boolean liquidSignificant = liquid > 0.05F;

        if (snowSignificant || liquidSignificant) {
            if (snowSignificant && liquid <= snowfall) {
                return snowLikeFromCode(fromCode);
            }
            // Liquid dominates (or snowfall is low while liquid is significant).
            return liquidFromRates(liquid, rain, showers, fromCode);
        }

        // Aggregate precipitation without component rates — trust the WMO code.
        if (precipitation > 0.05F) {
            return fromCode;
        }

        // Zero rates: keep code mapping (clear stays NONE; coded precip stays coded).
        return fromCode;
    }

    private static MeteorologicalPrecipitation snowLikeFromCode(MeteorologicalPrecipitation fromCode) {
        if (fromCode == MeteorologicalPrecipitation.SNOW_SHOWERS || fromCode == MeteorologicalPrecipitation.SHOWERS) {
            return MeteorologicalPrecipitation.SNOW_SHOWERS;
        }
        return MeteorologicalPrecipitation.SNOW;
    }

    private static MeteorologicalPrecipitation liquidFromRates(
        float liquid,
        float rain,
        float showers,
        MeteorologicalPrecipitation fromCode
    ) {
        if (liquid < 0.5F) {
            return MeteorologicalPrecipitation.DRIZZLE;
        }
        if (showers > rain || fromCode == MeteorologicalPrecipitation.SHOWERS) {
            return MeteorologicalPrecipitation.SHOWERS;
        }
        return MeteorologicalPrecipitation.RAIN;
    }
}
