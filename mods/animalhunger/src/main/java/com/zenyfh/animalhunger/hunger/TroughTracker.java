package com.zenyfh.animalhunger.hunger;

import com.zenyfh.animalhunger.world.TroughBlockEntity;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

public final class TroughTracker {
    private static final Map<Level, Set<Long>> TROUGHS_BY_LEVEL = Collections.synchronizedMap(new WeakHashMap<>());

    private TroughTracker() {
    }

    public static void register(Level level, BlockPos pos) {
        if (level == null || level.isClientSide()) {
            return;
        }
        synchronized (TROUGHS_BY_LEVEL) {
            TROUGHS_BY_LEVEL.computeIfAbsent(level, ignored -> Collections.newSetFromMap(new java.util.HashMap<>())).add(pos.asLong());
        }
    }

    public static void unregister(Level level, BlockPos pos) {
        if (level == null || level.isClientSide()) {
            return;
        }
        synchronized (TROUGHS_BY_LEVEL) {
            Set<Long> positions = TROUGHS_BY_LEVEL.get(level);
            if (positions != null) {
                positions.remove(pos.asLong());
                if (positions.isEmpty()) {
                    TROUGHS_BY_LEVEL.remove(level);
                }
            }
        }
    }

    public static BlockPos nearestTroughWithFood(LivingEntity entity, int radius) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return null;
        }
        Set<Long> snapshot;
        synchronized (TROUGHS_BY_LEVEL) {
            Set<Long> positions = TROUGHS_BY_LEVEL.get(level);
            if (positions == null || positions.isEmpty()) {
                AnimalHungerPerf.troughSearch(0, false);
                return null;
            }
            snapshot = Set.copyOf(positions);
        }

        BlockPos origin = entity.blockPosition();
        int radiusSquared = radius * radius;
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        int checked = 0;
        for (long packed : snapshot) {
            BlockPos pos = BlockPos.of(packed);
            double distance = origin.distSqr(pos);
            if (distance > radiusSquared || distance >= bestDistance) {
                continue;
            }
            checked++;
            if (!level.hasChunkAt(pos)) {
                continue;
            }
            if (!(level.getBlockEntity(pos) instanceof TroughBlockEntity trough)) {
                unregister(level, pos);
                continue;
            }
            if (trough.hasFoodFor(entity)) {
                best = pos;
                bestDistance = distance;
            }
        }
        AnimalHungerPerf.troughSearch(checked, best != null);
        return best;
    }
}
