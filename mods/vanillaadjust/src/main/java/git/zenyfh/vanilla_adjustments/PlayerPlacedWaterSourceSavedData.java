package git.zenyfh.vanilla_adjustments;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

public final class PlayerPlacedWaterSourceSavedData extends SavedData {
    private static final String DATA_NAME = Vanilla_adjustments.MODID + "_player_placed_water_sources";
    private static final String SOURCES_KEY = "sources";

    private final LongSet playerPlacedWaterSources = new LongOpenHashSet();

    public static PlayerPlacedWaterSourceSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(PlayerPlacedWaterSourceSavedData::new, PlayerPlacedWaterSourceSavedData::load),
                DATA_NAME
        );
    }

    private static PlayerPlacedWaterSourceSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        PlayerPlacedWaterSourceSavedData data = new PlayerPlacedWaterSourceSavedData();
        for (long source : tag.getLongArray(SOURCES_KEY)) {
            data.playerPlacedWaterSources.add(source);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray(SOURCES_KEY, playerPlacedWaterSources.toLongArray());
        return tag;
    }

    public boolean contains(long pos) {
        return playerPlacedWaterSources.contains(pos);
    }

    public void add(long pos) {
        if (playerPlacedWaterSources.add(pos)) {
            setDirty();
        }
    }

    public void remove(long pos) {
        if (playerPlacedWaterSources.remove(pos)) {
            setDirty();
        }
    }
}
