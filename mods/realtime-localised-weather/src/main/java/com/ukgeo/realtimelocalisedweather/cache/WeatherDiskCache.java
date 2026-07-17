package com.ukgeo.realtimelocalisedweather.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.google.gson.reflect.TypeToken;
import com.ukgeo.realtimelocalisedweather.weather.GameplaySeverity;
import com.ukgeo.realtimelocalisedweather.weather.MeteorologicalPrecipitation;
import com.ukgeo.realtimelocalisedweather.weather.ResolvedPrecipitation;
import com.ukgeo.realtimelocalisedweather.weather.ServerWeatherSnapshot;
import com.ukgeo.realtimelocalisedweather.weather.WeatherTileKey;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

public final class WeatherDiskCache {
    private static final Gson GSON = new GsonBuilder()
        .registerTypeAdapter(Instant.class, new TypeAdapter<Instant>() {
            @Override
            public void write(JsonWriter out, Instant value) throws java.io.IOException {
                out.value(value == null ? null : value.toString());
            }

            @Override
            public Instant read(JsonReader in) throws java.io.IOException {
                String value = in.nextString();
                return value == null ? null : Instant.parse(value);
            }
        })
        .setPrettyPrinting()
        .create();
    private static final Type LIST_TYPE = new TypeToken<List<Entry>>() {}.getType();
    private final Path path;

    public WeatherDiskCache(Path path) {
        this.path = path;
    }

    public void write(Map<WeatherTileKey, ServerWeatherSnapshot> snapshots) throws IOException {
        List<Entry> entries = snapshots.entrySet().stream()
            .map(entry -> Entry.from(entry.getKey(), entry.getValue()))
            .toList();
        Files.createDirectories(path.getParent());
        Files.writeString(path, GSON.toJson(entries));
    }

    public Map<WeatherTileKey, ServerWeatherSnapshot> read() throws IOException {
        if (!Files.exists(path)) {
            return Map.of();
        }
        List<Entry> entries = GSON.fromJson(Files.readString(path), LIST_TYPE);
        Map<WeatherTileKey, ServerWeatherSnapshot> result = new HashMap<>();
        if (entries != null) {
            for (Entry entry : entries) {
                result.put(entry.key(), entry.snapshot());
            }
        }
        return result;
    }

    private record Entry(String dimension, int tileX, int tileZ, ServerWeatherSnapshot snapshot) {
        static Entry from(WeatherTileKey key, ServerWeatherSnapshot snapshot) {
            return new Entry(key.dimension().location().toString(), key.tileX(), key.tileZ(), snapshot);
        }

        WeatherTileKey key() {
            return new WeatherTileKey(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension)), tileX, tileZ);
        }
    }
}
