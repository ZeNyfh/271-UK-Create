package git.zenyfh.vanilla_adjustments;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

public final class PlayerPlacedWaterSourceSavedData extends SavedData {
    private static final String DATA_NAME = Vanilla_adjustments.MODID + "_player_placed_water_sources";
    private static final String LEGACY_SOURCES_KEY = "sources";
    private static final String NON_NATURAL_WATER_KEY = "nonNaturalWaterSources";
    private static final String NON_NATURAL_ICE_KEY = "nonNaturalIceBlocks";

    private final LongSet nonNaturalWaterSources = new LongOpenHashSet();
    private final LongSet nonNaturalIceBlocks = new LongOpenHashSet();

    public static PlayerPlacedWaterSourceSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(PlayerPlacedWaterSourceSavedData::new, PlayerPlacedWaterSourceSavedData::load),
                DATA_NAME
        );
    }

    private static PlayerPlacedWaterSourceSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        PlayerPlacedWaterSourceSavedData data = new PlayerPlacedWaterSourceSavedData();
        for (long source : tag.getLongArray(LEGACY_SOURCES_KEY)) {
            data.nonNaturalWaterSources.add(source);
        }
        for (long source : tag.getLongArray(NON_NATURAL_WATER_KEY)) {
            data.nonNaturalWaterSources.add(source);
        }
        for (long ice : tag.getLongArray(NON_NATURAL_ICE_KEY)) {
            data.nonNaturalIceBlocks.add(ice);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray(NON_NATURAL_WATER_KEY, nonNaturalWaterSources.toLongArray());
        tag.putLongArray(NON_NATURAL_ICE_KEY, nonNaturalIceBlocks.toLongArray());
        return tag;
    }

    public boolean containsNonNaturalWater(long pos) {
        return nonNaturalWaterSources.contains(pos);
    }

    public boolean addNonNaturalWater(long pos) {
        if (nonNaturalWaterSources.add(pos)) {
            setDirty();
            return true;
        }
        return false;
    }

    public int addNonNaturalWaterBatch(LongSet positions) {
        int added = 0;
        for (long pos : positions) {
            if (nonNaturalWaterSources.add(pos)) {
                added++;
            }
        }
        if (added > 0) {
            setDirty();
        }
        return added;
    }

    public boolean removeNonNaturalWater(long pos) {
        if (nonNaturalWaterSources.remove(pos)) {
            setDirty();
            return true;
        }
        return false;
    }

    public boolean containsNonNaturalIce(long pos) {
        return nonNaturalIceBlocks.contains(pos);
    }

    public boolean addNonNaturalIce(long pos) {
        if (nonNaturalIceBlocks.add(pos)) {
            setDirty();
            return true;
        }
        return false;
    }

    public boolean removeNonNaturalIce(long pos) {
        if (nonNaturalIceBlocks.remove(pos)) {
            setDirty();
            return true;
        }
        return false;
    }

    public void removeAll(long pos) {
        boolean changed = nonNaturalWaterSources.remove(pos);
        changed |= nonNaturalIceBlocks.remove(pos);
        if (changed) {
            setDirty();
        }
    }
}
