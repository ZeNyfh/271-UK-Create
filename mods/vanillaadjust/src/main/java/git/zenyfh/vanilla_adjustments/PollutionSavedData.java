package git.zenyfh.vanilla_adjustments;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

public final class PollutionSavedData extends SavedData {
    private static final String DATA_NAME = Vanilla_adjustments.MODID + "_pollution";

    private final Map<Long, Double> pollutionByChunk = new HashMap<>();

    public static PollutionSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(PollutionSavedData::new, PollutionSavedData::load),
                DATA_NAME
        );
    }

    private static PollutionSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        PollutionSavedData data = new PollutionSavedData();
        ListTag entries = tag.getList("chunks", Tag.TAG_COMPOUND);
        for (Tag entryTag : entries) {
            CompoundTag entry = (CompoundTag) entryTag;
            double amount = entry.getDouble("pollution");
            if (amount >= storageThreshold()) {
                data.pollutionByChunk.put(entry.getLong("chunk"), clampPollution(amount));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag entries = new ListTag();
        double threshold = storageThreshold();
        for (Map.Entry<Long, Double> entry : pollutionByChunk.entrySet()) {
            double amount = entry.getValue();
            if (amount >= threshold) {
                CompoundTag chunkTag = new CompoundTag();
                chunkTag.putLong("chunk", entry.getKey());
                chunkTag.putDouble("pollution", amount);
                entries.add(chunkTag);
            }
        }
        tag.put("chunks", entries);
        return tag;
    }

    public Map<Long, Double> pollutionByChunk() {
        return pollutionByChunk;
    }

    public double getPollution(long chunkKey) {
        return pollutionByChunk.getOrDefault(chunkKey, 0.0);
    }

    public double getPollution(int chunkX, int chunkZ) {
        return getPollution(ChunkPos.asLong(chunkX, chunkZ));
    }

    public void addPollution(long chunkKey, double amount) {
        if (amount <= 0.0) {
            return;
        }
        pollutionByChunk.merge(chunkKey, amount, (oldValue, added) -> clampPollution(oldValue + added));
        setDirty();
    }

    public void setPollution(long chunkKey, double amount) {
        double threshold = storageThreshold();
        if (amount < threshold) {
            pollutionByChunk.remove(chunkKey);
        } else {
            pollutionByChunk.put(chunkKey, clampPollution(amount));
        }
        setDirty();
    }

    public void clear() {
        if (!pollutionByChunk.isEmpty()) {
            pollutionByChunk.clear();
            setDirty();
        }
    }

    public void prune() {
        double threshold = storageThreshold();
        boolean changed = false;
        for (Iterator<Map.Entry<Long, Double>> iterator = pollutionByChunk.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<Long, Double> entry = iterator.next();
            if (entry.getValue() < threshold) {
                iterator.remove();
                changed = true;
            }
        }
        if (changed) {
            setDirty();
        }
    }

    private static double clampPollution(double amount) {
        return Math.max(0.0, Math.min(amount, VanillaAdjustConfig.POLLUTION_MAX_PER_CHUNK.get()));
    }

    private static double storageThreshold() {
        return Math.max(0.0, VanillaAdjustConfig.POLLUTION_STORAGE_THRESHOLD.get());
    }
}
