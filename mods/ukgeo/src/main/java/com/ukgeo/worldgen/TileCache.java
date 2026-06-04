package com.ukgeo.worldgen;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TileCache<K, V> {
    private final int maxEntries;
    private final LinkedHashMap<K, V> cache;
    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlightLoads = new ConcurrentHashMap<>();
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

    public V get(K key, ThrowingLoader<K, V> loader) throws IOException {
        synchronized (this) {
            V value = cache.get(key);
            if (value != null) {
                hits++;
                return value;
            }
            misses++;
        }

        CompletableFuture<V> loadFuture = new CompletableFuture<>();
        CompletableFuture<V> existingFuture = inFlightLoads.putIfAbsent(key, loadFuture);
        if (existingFuture != null) {
            return awaitLoad(key, existingFuture);
        }

        try {
            V loaded = loader.load(key);
            synchronized (this) {
                V existing = cache.get(key);
                if (existing != null) {
                    loadFuture.complete(existing);
                    return existing;
                }
                cache.put(key, loaded);
                loadFuture.complete(loaded);
                return loaded;
            }
        } catch (IOException | RuntimeException ex) {
            loadFuture.completeExceptionally(ex);
            throw ex;
        } finally {
            inFlightLoads.remove(key, loadFuture);
        }
    }

    private V awaitLoad(K key, CompletableFuture<V> future) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while loading tile " + key, ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("Failed to load tile " + key, cause);
        }
    }

    public synchronized String stats() {
        return "entries=%d hits=%d misses=%d".formatted(cache.size(), hits, misses);
    }

    @FunctionalInterface
    public interface ThrowingLoader<K, V> {
        V load(K key) throws IOException;
    }
}
