package com.ukgeo.worldgen;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TileCache<K, V> {
    private static final boolean DEBUG_TILE_CACHE = Boolean.getBoolean("ukgeo.debugTileCache");
    private static final long SLOW_TILE_LOAD_WARN_MS = Long.getLong("ukgeo.slowTileLoadWarnMs", 100L);
    private static final long TILE_CACHE_LOG_INTERVAL = Long.getLong("ukgeo.tileCacheLogInterval", 4096L);
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
                maybeLogStats();
                return value;
            }
            misses++;
            maybeLogStats();
        }

        CompletableFuture<V> loadFuture = new CompletableFuture<>();
        CompletableFuture<V> existingFuture = inFlightLoads.putIfAbsent(key, loadFuture);
        if (existingFuture != null) {
            return awaitLoad(key, existingFuture);
        }

        try {
            long startNanos = System.nanoTime();
            V loaded = loader.load(key);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            if (DEBUG_TILE_CACHE && elapsedMs >= SLOW_TILE_LOAD_WARN_MS) {
                UkGeoMod.LOGGER.warn("UKGeo tile cache slow load key={} elapsed={}ms threshold={}ms", key, elapsedMs, SLOW_TILE_LOAD_WARN_MS);
            }
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

    private synchronized void maybeLogStats() {
        if (!DEBUG_TILE_CACHE) {
            return;
        }
        long total = hits + misses;
        if (total > 0 && total % TILE_CACHE_LOG_INTERVAL == 0) {
            UkGeoMod.LOGGER.info("UKGeo tile cache {}", stats());
        }
    }

    @FunctionalInterface
    public interface ThrowingLoader<K, V> {
        V load(K key) throws IOException;
    }
}
