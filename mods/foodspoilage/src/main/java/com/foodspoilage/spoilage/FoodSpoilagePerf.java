package com.foodspoilage.spoilage;

import com.foodspoilage.FoodSpoilage;
import java.util.concurrent.atomic.LongAdder;

public final class FoodSpoilagePerf {
    public static final boolean DEBUG = Boolean.getBoolean("foodspoilage.debugPerf");
    private static final long SLOW_SCAN_WARN_MS = Long.getLong("foodspoilage.slowInventoryScanWarnMs", 50L);
    private static final LongAdder playerInventoryScans = new LongAdder();
    private static final LongAdder menuScans = new LongAdder();
    private static final LongAdder itemEntityChecks = new LongAdder();
    private static final LongAdder skippedCleanScans = new LongAdder();

    private FoodSpoilagePerf() {
    }

    public static void playerInventoryScan(long nanos, boolean changed) {
        record(playerInventoryScans, "player inventory", nanos, changed);
    }

    public static void menuScan(long nanos, boolean changed) {
        record(menuScans, "menu", nanos, changed);
    }

    public static void itemEntityCheck() {
        if (DEBUG) {
            itemEntityChecks.increment();
        }
    }

    private static void record(LongAdder counter, String label, long nanos, boolean changed) {
        if (!DEBUG) {
            return;
        }
        counter.increment();
        if (!changed) {
            skippedCleanScans.increment();
        }
        long elapsedMs = nanos / 1_000_000L;
        if (elapsedMs >= SLOW_SCAN_WARN_MS) {
            FoodSpoilage.LOGGER.warn("FoodSpoilage slow {} scan elapsed={}ms threshold={}ms changed={}", label, elapsedMs, SLOW_SCAN_WARN_MS, changed);
        }
        long scans = playerInventoryScans.sum() + menuScans.sum();
        if (scans > 0 && scans % 200 == 0) {
            FoodSpoilage.LOGGER.info(
                "FoodSpoilage perf playerInventoryScans={} menuScans={} itemEntityChecks={} skippedCleanScans={}",
                playerInventoryScans.sum(),
                menuScans.sum(),
                itemEntityChecks.sum(),
                skippedCleanScans.sum()
            );
        }
    }
}
