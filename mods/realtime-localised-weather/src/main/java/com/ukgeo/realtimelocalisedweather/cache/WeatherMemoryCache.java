package com.ukgeo.realtimelocalisedweather.cache;

import com.ukgeo.realtimelocalisedweather.weather.ServerWeatherSnapshot;
import com.ukgeo.realtimelocalisedweather.weather.WeatherTileKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class WeatherMemoryCache {
    private final Map<WeatherTileKey, Entry> entries = new ConcurrentHashMap<>();

    public void put(WeatherTileKey key, ServerWeatherSnapshot snapshot, Instant cachedAt) {
        entries.put(key, new Entry(snapshot, cachedAt));
    }

    public Optional<Entry> get(WeatherTileKey key) {
        return Optional.ofNullable(entries.get(key));
    }

    public Optional<ServerWeatherSnapshot> getUsable(WeatherTileKey key, Instant now, Duration hardExpiry) {
        Entry entry = entries.get(key);
        if (entry == null || entry.cachedAt.plus(hardExpiry).isBefore(now)) {
            return Optional.empty();
        }
        return Optional.of(entry.snapshot);
    }

    public void prune(Instant now, Duration hardExpiry) {
        entries.entrySet().removeIf(entry -> entry.getValue().cachedAt.plus(hardExpiry).isBefore(now));
    }

    public int size() {
        return entries.size();
    }

    public record Entry(ServerWeatherSnapshot snapshot, Instant cachedAt) {
        public boolean isStale(Instant now, Duration staleAfter) {
            return cachedAt.plus(staleAfter).isBefore(now);
        }
    }
}
