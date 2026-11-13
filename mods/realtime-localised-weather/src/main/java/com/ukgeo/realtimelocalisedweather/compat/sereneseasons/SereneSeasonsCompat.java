package com.ukgeo.realtimelocalisedweather.compat.sereneseasons;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.fml.ModList;

public final class SereneSeasonsCompat {
    private static final int FALLBACK_CYCLE_DURATION_TICKS = 2_304_000;

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
            Object state = helperClass.getMethod("getSeasonState", Level.class).invoke(null, level);
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

    /** {@code SeasonTime.ZERO.getCycleDuration()}, or a Serene-default fallback if unavailable. */
    public static int getCycleDurationTicks() {
        if (!isLoaded()) {
            return FALLBACK_CYCLE_DURATION_TICKS;
        }
        try {
            Class<?> seasonTimeClass = Class.forName("sereneseasons.season.SeasonTime");
            Object zero = seasonTimeClass.getField("ZERO").get(null);
            Object duration = seasonTimeClass.getMethod("getCycleDuration").invoke(zero);
            if (duration instanceof Integer value && value > 0) {
                return value;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // fall through
        }
        return FALLBACK_CYCLE_DURATION_TICKS;
    }

    public static Integer getSeasonCycleTicks(ServerLevel level) {
        if (!isLoaded() || level == null) {
            return null;
        }
        try {
            Object savedData = getSeasonSavedData(level);
            if (savedData == null) {
                return null;
            }
            Field field = savedData.getClass().getField("seasonCycleTicks");
            Object value = field.get(savedData);
            return value instanceof Integer ticks ? ticks : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    public static boolean setSeasonCycleTicks(ServerLevel level, int ticks) {
        if (!isLoaded() || level == null) {
            return false;
        }
        try {
            Object savedData = getSeasonSavedData(level);
            if (savedData == null) {
                return false;
            }
            Field field = savedData.getClass().getField("seasonCycleTicks");
            field.setInt(savedData, ticks);
            savedData.getClass().getMethod("setDirty").invoke(savedData);
            Class<?> handlerClass = Class.forName("sereneseasons.season.SeasonHandler");
            handlerClass.getMethod("sendSeasonUpdate", Level.class).invoke(null, level);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    public static boolean setDoSeasonCycle(ServerLevel level, boolean enabled) {
        if (!isLoaded() || level == null) {
            return false;
        }
        try {
            GameRules.Key<GameRules.BooleanValue> key = resolveDoSeasonCycleKey();
            if (key == null) {
                return false;
            }
            MinecraftServer server = level.getServer();
            level.getGameRules().getRule(key).set(enabled, server);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static Object getSeasonSavedData(ServerLevel level) throws ReflectiveOperationException {
        Class<?> handlerClass = Class.forName("sereneseasons.season.SeasonHandler");
        return handlerClass.getMethod("getSeasonSavedData", Level.class).invoke(null, level);
    }

    @SuppressWarnings("unchecked")
    private static GameRules.Key<GameRules.BooleanValue> resolveDoSeasonCycleKey() throws ReflectiveOperationException {
        try {
            Class<?> ssGameRules = Class.forName("sereneseasons.api.SSGameRules");
            Object key = ssGameRules.getField("RULE_DOSEASONCYCLE").get(null);
            if (key instanceof GameRules.Key<?> typed) {
                return (GameRules.Key<GameRules.BooleanValue>) typed;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // fall through to string lookup
        }
        return lookupGameRuleKey("doSeasonCycle");
    }

    @SuppressWarnings("unchecked")
    private static GameRules.Key<GameRules.BooleanValue> lookupGameRuleKey(String id) throws ReflectiveOperationException {
        Field typesField = GameRules.class.getDeclaredField("GAME_RULE_TYPES");
        typesField.setAccessible(true);
        Object raw = typesField.get(null);
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        for (Object keyObj : map.keySet()) {
            if (!(keyObj instanceof GameRules.Key<?> key)) {
                continue;
            }
            Method getId = key.getClass().getMethod("getId");
            Object keyId = getId.invoke(key);
            if (id.equals(String.valueOf(keyId))) {
                return (GameRules.Key<GameRules.BooleanValue>) key;
            }
        }
        return null;
    }

    private static String invokeName(Object target, String methodName) throws ReflectiveOperationException {
        if (target == null) {
            return "unknown";
        }
        Object value = target.getClass().getMethod(methodName).invoke(target);
        return value == null ? "unknown" : value.toString();
    }
}
