package com.ukgeo.worldgen;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class UkGeoOilIntegration {
    private static final String CREATE_DIESEL_GENERATORS_MOD_ID = "createdieselgenerators";
    private static final String CREATE_DIESEL_GENERATORS_OIL_DATA_CLASS = "com.jesz.createdieselgenerators.world.OilChunksSavedData";
    private static final boolean DEBUG_OIL_GEN = Boolean.getBoolean("ukgeo.debugOilGen");
    private static final int MAX_OIL_UPDATES_PER_TICK = Integer.getInteger("ukgeo.maxOilUpdatesPerTick", 1024);

    private static final ConcurrentHashMap<OilQueueKey, Integer> pendingOilAmounts = new ConcurrentHashMap<>();
    private static volatile boolean createDieselGeneratorsOilLookupAttempted;
    private static volatile Method createDieselGeneratorsSetOilAmount;
    private static volatile boolean missingApiWarningLogged;

    private UkGeoOilIntegration() {
    }

    public static void enqueue(ServerLevel level, ChunkPos chunkPos, int amount) {
        if (!ModList.get().isLoaded(CREATE_DIESEL_GENERATORS_MOD_ID)) {
            return;
        }
        pendingOilAmounts.put(new OilQueueKey(level.dimension(), chunkPos.toLong()), amount);
        if (DEBUG_OIL_GEN) {
            UkGeoMod.LOGGER.info("UKGeo oil enqueued dimension={} chunk={} amount={}mB", level.dimension().location(), chunkPos, amount);
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (pendingOilAmounts.isEmpty()) {
            return;
        }
        Optional<Method> setOilAmount = createDieselGeneratorsSetOilAmount();
        if (setOilAmount.isEmpty()) {
            pendingOilAmounts.clear();
            return;
        }

        int applied = 0;
        for (Map.Entry<OilQueueKey, Integer> entry : new ArrayList<>(pendingOilAmounts.entrySet())) {
            if (applied >= MAX_OIL_UPDATES_PER_TICK) {
                break;
            }

            OilQueueKey key = entry.getKey();
            Integer amount = entry.getValue();
            if (!pendingOilAmounts.remove(key, amount)) {
                continue;
            }

            ServerLevel level = event.getServer().getLevel(key.dimension());
            if (level == null) {
                continue;
            }

            ChunkPos chunkPos = new ChunkPos(key.chunkPosLong());
            try {
                setOilAmount.get().invoke(null, level, chunkPos, amount);
                applied++;
                if (DEBUG_OIL_GEN) {
                    UkGeoMod.LOGGER.info("UKGeo oil applied dimension={} chunk={} amount={}mB", key.dimension().location(), chunkPos, amount);
                }
            } catch (ReflectiveOperationException | RuntimeException ex) {
                UkGeoMod.LOGGER.warn("Could not set Create: Diesel Generators oil amount for chunk {}: {}", chunkPos, ex.getMessage());
            }
        }
    }

    private static Optional<Method> createDieselGeneratorsSetOilAmount() {
        if (!ModList.get().isLoaded(CREATE_DIESEL_GENERATORS_MOD_ID)) {
            return Optional.empty();
        }
        Method current = createDieselGeneratorsSetOilAmount;
        if (current != null) {
            return Optional.of(current);
        }
        if (createDieselGeneratorsOilLookupAttempted) {
            logMissingApiOnce();
            return Optional.empty();
        }
        synchronized (UkGeoOilIntegration.class) {
            if (createDieselGeneratorsSetOilAmount != null) {
                return Optional.of(createDieselGeneratorsSetOilAmount);
            }
            if (createDieselGeneratorsOilLookupAttempted) {
                logMissingApiOnce();
                return Optional.empty();
            }
            createDieselGeneratorsOilLookupAttempted = true;
            try {
                Class<?> savedDataClass = Class.forName(CREATE_DIESEL_GENERATORS_OIL_DATA_CLASS);
                createDieselGeneratorsSetOilAmount = savedDataClass.getMethod("setChunkOilAmount", ServerLevel.class, ChunkPos.class, int.class);
                UkGeoMod.LOGGER.info("Create: Diesel Generators oil integration enabled");
                return Optional.of(createDieselGeneratorsSetOilAmount);
            } catch (ReflectiveOperationException ex) {
                UkGeoMod.LOGGER.warn("Create: Diesel Generators is loaded, but UKGeo could not find its oil chunk API: {}", ex.getMessage());
                return Optional.empty();
            }
        }
    }

    private static void logMissingApiOnce() {
        if (!missingApiWarningLogged) {
            synchronized (UkGeoOilIntegration.class) {
                if (!missingApiWarningLogged) {
                    missingApiWarningLogged = true;
                    UkGeoMod.LOGGER.warn("Create: Diesel Generators oil integration is unavailable; dropping queued UKGeo oil updates");
                }
            }
        }
    }

    private record OilQueueKey(ResourceKey<Level> dimension, long chunkPosLong) {
    }
}
