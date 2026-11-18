package com.ukgeo.worldgen.pregen;

import com.ukgeo.worldgen.UkGeoChunkGenerator;
import com.ukgeo.worldgen.UkGeoMod;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;

/**
 * Optional Chunky pregen watchdog: when generation rate collapses (or empty-origin cache
 * nears cap), pause the task, clear UKGeo ore caches, then resume. Bound via reflection —
 * no compile dependency on Chunky.
 */
public final class ChunkyPregenWatchdog {
    private static final boolean ENABLED = Boolean.getBoolean("ukgeo.chunkyWatchdog");
    private static final double LOW_RATE = Double.parseDouble(System.getProperty("ukgeo.chunkyWatchdog.lowRate", "2.0"));
    private static final int BAD_SAMPLES = Integer.getInteger("ukgeo.chunkyWatchdog.badSamples", 5);
    private static final long COOLDOWN_MS = Long.getLong("ukgeo.chunkyWatchdog.cooldownSeconds", 90L) * 1000L;
    private static final long PAUSE_MS = Long.getLong("ukgeo.chunkyWatchdog.pauseMillis", 3000L);

    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean DISABLED = new AtomicBoolean();
    private static final AtomicBoolean RECOVERING = new AtomicBoolean();
    private static final AtomicInteger BAD_SAMPLE_COUNT = new AtomicInteger();
    private static final AtomicLong COOLDOWN_UNTIL_MS = new AtomicLong();

    private static volatile MinecraftServer server;
    private static volatile Object api;
    private static Method pauseTask;
    private static Method continueTask;

    private ChunkyPregenWatchdog() {
    }

    public static void tryStart(MinecraftServer minecraftServer) {
        if (!ENABLED || DISABLED.get() || STARTED.get()) {
            return;
        }
        if (!ModList.get().isLoaded("chunky")) {
            return;
        }
        server = minecraftServer;
        if (!bindApi()) {
            return;
        }
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        UkGeoMod.LOGGER.info(
            "UKGeo Chunky pregen watchdog enabled (lowRate={}, badSamples={}, cooldownSeconds={}, pauseMillis={})",
            LOW_RATE,
            BAD_SAMPLES,
            COOLDOWN_MS / 1000L,
            PAUSE_MS
        );
    }

    /** Ops: pause → clear caches → resume for the given Chunky world name. */
    public static boolean recoverNow(String world) {
        if (DISABLED.get() || api == null || world == null || world.isBlank()) {
            return false;
        }
        MinecraftServer s = server;
        if (s == null) {
            return false;
        }
        if (!RECOVERING.compareAndSet(false, true)) {
            return false;
        }
        BAD_SAMPLE_COUNT.set(0);
        s.execute(() -> runRecovery(world, Double.NaN, -1L, true));
        return true;
    }

    private static boolean bindApi() {
        try {
            Class<?> providerClass = Class.forName("org.popcraft.chunky.ChunkyProvider");
            Object chunky = providerClass.getMethod("get").invoke(null);
            Object apiObj = chunky.getClass().getMethod("getApi").invoke(chunky);
            Method onProgress = apiObj.getClass().getMethod("onGenerationProgress", Consumer.class);
            pauseTask = apiObj.getClass().getMethod("pauseTask", String.class);
            continueTask = apiObj.getClass().getMethod("continueTask", String.class);
            api = apiObj;
            Consumer<Object> listener = ChunkyPregenWatchdog::onProgressEvent;
            onProgress.invoke(apiObj, listener);
            return true;
        } catch (Throwable ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage();
            Throwable cause = ex.getCause();
            String causeMsg = cause == null || cause.getMessage() == null ? "" : cause.getMessage();
            if (msg.toLowerCase().contains("not loaded") || causeMsg.toLowerCase().contains("not loaded")) {
                return false;
            }
            DISABLED.set(true);
            UkGeoMod.LOGGER.warn("UKGeo Chunky pregen watchdog disabled for session: {}", ex.toString());
            return false;
        }
    }

    private static void onProgressEvent(Object event) {
        if (DISABLED.get() || api == null) {
            return;
        }
        try {
            String world = (String) event.getClass().getMethod("world").invoke(event);
            double rate = ((Number) event.getClass().getMethod("rate").invoke(event)).doubleValue();
            long chunks = ((Number) event.getClass().getMethod("chunks").invoke(event)).longValue();
            boolean complete = (Boolean) event.getClass().getMethod("complete").invoke(event);

            if (complete) {
                BAD_SAMPLE_COUNT.set(0);
                return;
            }
            if (rate >= LOW_RATE) {
                BAD_SAMPLE_COUNT.set(0);
                return;
            }

            int bad = BAD_SAMPLE_COUNT.incrementAndGet();
            int emptySize = UkGeoChunkGenerator.pregenEmptyOriginCacheSize();
            int emptyMax = UkGeoChunkGenerator.pregenEmptyOriginCacheMax();
            boolean earlyEmpty = emptyMax > 0 && emptySize >= (int) (emptyMax * 0.9) && rate < LOW_RATE;
            boolean samplesReached = bad >= BAD_SAMPLES;
            if (!(samplesReached || earlyEmpty)) {
                return;
            }
            if (System.currentTimeMillis() < COOLDOWN_UNTIL_MS.get()) {
                return;
            }
            if (!RECOVERING.compareAndSet(false, true)) {
                return;
            }
            BAD_SAMPLE_COUNT.set(0);
            MinecraftServer s = server;
            if (s == null) {
                RECOVERING.set(false);
                return;
            }
            s.execute(() -> runRecovery(world, rate, chunks, false));
        } catch (Throwable ex) {
            UkGeoMod.LOGGER.warn("UKGeo Chunky pregen watchdog progress handler failed: {}", ex.toString());
        }
    }

    private static void runRecovery(String world, double rate, long chunks, boolean manual) {
        MinecraftServer s = server;
        Object apiRef = api;
        if (s == null || apiRef == null) {
            RECOVERING.set(false);
            return;
        }
        try {
            if (manual) {
                UkGeoMod.LOGGER.warn("UKGeo Chunky watchdog: manual recover for world={}", world);
            } else {
                UkGeoMod.LOGGER.warn(
                    "UKGeo Chunky watchdog: slow pregen world={} rate={} chunks={} — pausing to clear caches",
                    world,
                    rate,
                    chunks
                );
            }
            pauseTask.invoke(apiRef, world);
            UkGeoChunkGenerator.clearPregenCaches();
            Thread sleeper = new Thread(() -> {
                try {
                    Thread.sleep(PAUSE_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                s.execute(() -> {
                    try {
                        continueTask.invoke(apiRef, world);
                        UkGeoMod.LOGGER.info("UKGeo Chunky watchdog: resumed world={} after cache clear", world);
                    } catch (Throwable ex) {
                        UkGeoMod.LOGGER.warn("UKGeo Chunky watchdog: continueTask failed: {}", ex.toString());
                    } finally {
                        COOLDOWN_UNTIL_MS.set(System.currentTimeMillis() + COOLDOWN_MS);
                        RECOVERING.set(false);
                    }
                });
            }, "ukgeo-chunky-watchdog");
            sleeper.setDaemon(true);
            sleeper.start();
        } catch (Throwable ex) {
            UkGeoMod.LOGGER.warn("UKGeo Chunky watchdog: recovery failed: {}", ex.toString());
            RECOVERING.set(false);
        }
    }
}
