package com.ukgeo.realtimelocalisedweather.compat.sereneseasons;

import com.ukgeo.realtimelocalisedweather.RealtimeLocalisedWeatherMod;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

public final class RealtimeSeasonSavedData extends SavedData {
    private static final String DATA_NAME = RealtimeLocalisedWeatherMod.MOD_ID + "_realtime_season";
    private static final String ANCHOR_KEY = "AnchorEpochMillis";

    private long anchorEpochMillis;

    public RealtimeSeasonSavedData() {
    }

    public static RealtimeSeasonSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(RealtimeSeasonSavedData::new, RealtimeSeasonSavedData::load),
            DATA_NAME
        );
    }

    private static RealtimeSeasonSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        RealtimeSeasonSavedData data = new RealtimeSeasonSavedData();
        data.anchorEpochMillis = tag.getLong(ANCHOR_KEY);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong(ANCHOR_KEY, anchorEpochMillis);
        return tag;
    }

    public long getAnchorEpochMillis() {
        return anchorEpochMillis;
    }

    public boolean hasAnchor() {
        return anchorEpochMillis > 0L;
    }

    public void setAnchorEpochMillis(long anchorEpochMillis) {
        if (this.anchorEpochMillis != anchorEpochMillis) {
            this.anchorEpochMillis = anchorEpochMillis;
            setDirty();
        }
    }
}
