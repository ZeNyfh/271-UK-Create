package com.ukgeo.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class BoundedCacheTest {
    @Test
    void clearEmptiesValuesAndFifo() {
        BoundedCache<String, Integer> cache = new BoundedCache<>(32);
        assertNull(cache.putIfAbsent("a", 1));
        assertEquals(1, cache.size());
        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get("a"));
    }
}
