package com.ukgeo.realtimelocalisedweather.compat.sereneseasons;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.fml.ModList;

public final class SereneSeasonsCompat {
    private SereneSeasonsCompat() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("sereneseasons");
    }

    public static SereneSeasonSnapshot snapshot(ServerLevel level, Holder<Biome> biome) {
        if (!isLoaded()) {
            return SereneSeasonSnapshot.ABSENT;
        }
        try {
            Class<?> helperClass = Class.forName("sereneseasons.api.season.SeasonHelper");
            Object state = helperClass.getMethod("getSeasonState", net.minecraft.world.level.Level.class).invoke(null, level);
            boolean tropical = biome != null && (boolean) helperClass.getMethod("usesTropicalSeasons", Holder.class).invoke(null, biome);
            String season = invokeName(state, "getSeason");
            String subSeason = invokeName(state, "getSubSeason");
            String tropicalSeason = invokeName(state, "getTropicalSeason");
            boolean winter = subSeason.contains("WINTER") || season.contains("WINTER");
            return new SereneSeasonSnapshot(true, winter, tropical, season, subSeason, tropicalSeason);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return SereneSeasonSnapshot.ABSENT;
        }
    }

    private static String invokeName(Object target, String methodName) throws ReflectiveOperationException {
        if (target == null) {
            return "unknown";
        }
        Object value = target.getClass().getMethod(methodName).invoke(target);
        return value == null ? "unknown" : value.toString();
    }
}
