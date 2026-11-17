package com.ukgeo.worldgen;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Concurrent insertion-order cache; reads never wait for loading or eviction. */
final class BoundedCache<K, V> {
    private final int maxEntries;
    private final ConcurrentHashMap<K, V> values;
    private final ConcurrentLinkedQueue<K> fifoKeys;

    BoundedCache(int maxEntries) {
        this.maxEntries = Math.max(16, maxEntries);
        this.values = new ConcurrentHashMap<>(16);
        this.fifoKeys = new ConcurrentLinkedQueue<>();
    }

    V get(K key) {
        return values.get(key);
    }

    V putIfAbsent(K key, V value) {
        V existing = values.putIfAbsent(key, value);
        if (existing == null) {
            fifoKeys.add(key);
            trimToMax();
        }
        return existing;
    }

    private void trimToMax() {
        while (values.size() > maxEntries) {
            K victim = fifoKeys.poll();
            if (victim == null) {
                break;
            }
            values.remove(victim);
        }
    }
}
