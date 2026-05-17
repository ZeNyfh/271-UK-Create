package com.ukgeo.worldgen;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

public final class TileCache<K, V> {
    private final int maxEntries;
    private final LinkedHashMap<K, V> cache;
    private long hits;
    private long misses;

    public TileCache(int maxEntries) {
        this.maxEntries = maxEntries;
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > TileCache.this.maxEntries;
            }
        };
    }

    public synchronized V get(K key, ThrowingLoader<K, V> loader) throws IOException {
        V value = cache.get(key);
        if (value != null) {
            hits++;
            return value;
        }
        misses++;
        value = loader.load(key);
        cache.put(key, value);
        return value;
    }

    public synchronized String stats() {
        return "entries=%d hits=%d misses=%d".formatted(cache.size(), hits, misses);
    }

    @FunctionalInterface
    public interface ThrowingLoader<K, V> {
        V load(K key) throws IOException;
    }
}

