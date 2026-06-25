package com.zenyfh.animalhunger.hunger;

import com.zenyfh.animalhunger.AnimalHunger;
import java.util.concurrent.atomic.LongAdder;

public final class AnimalHungerPerf {
    public static final boolean DEBUG = Boolean.getBoolean("animalhunger.debugPerf");
    private static final long LOG_INTERVAL_SEARCHES = 1_000L;
    private static final LongAdder troughSearches = new LongAdder();
    private static final LongAdder troughChecks = new LongAdder();
    private static final LongAdder troughHits = new LongAdder();
    private static final LongAdder grassSearches = new LongAdder();
    private static final LongAdder grassChecks = new LongAdder();
    private static final LongAdder grassHits = new LongAdder();
    private static final LongAdder animalsTracked = new LongAdder();

    private AnimalHungerPerf() {
    }

    public static void animalTracked() {
        if (DEBUG) {
            animalsTracked.increment();
        }
    }

    public static void troughSearch(int checked, boolean hit) {
        if (!DEBUG) {
            return;
        }
        troughSearches.increment();
        troughChecks.add(checked);
        if (hit) {
            troughHits.increment();
        }
        maybeLog();
    }

    public static void grassSearch(int checked, boolean hit) {
        if (!DEBUG) {
            return;
        }
        grassSearches.increment();
        grassChecks.add(checked);
        if (hit) {
            grassHits.increment();
        }
        maybeLog();
    }

    private static void maybeLog() {
        long searches = troughSearches.sum() + grassSearches.sum();
        if (searches <= 0 || searches % LOG_INTERVAL_SEARCHES != 0) {
            return;
        }
        long trough = troughSearches.sum();
        long grass = grassSearches.sum();
        AnimalHunger.LOGGER.info(
            "AnimalHunger perf animalsTracked={} troughSearches={} troughHits={} avgTroughChecks={} grassSearches={} grassHits={} avgGrassChecks={}",
            animalsTracked.sum(),
            trough,
            troughHits.sum(),
            trough == 0 ? 0 : troughChecks.sum() / trough,
            grass,
            grassHits.sum(),
            grass == 0 ? 0 : grassChecks.sum() / grass
        );
    }
}
