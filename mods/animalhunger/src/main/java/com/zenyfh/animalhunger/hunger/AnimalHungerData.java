package com.zenyfh.animalhunger.hunger;

import com.zenyfh.animalhunger.config.AnimalHungerConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;

public final class AnimalHungerData {
    public static final String ROOT = "animalhunger";
    public static final String INITIALIZED = "initialized";
    public static final String HUNGER = "hunger";
    private static final String LAST_FED = "lastFedGameTime";
    private static final String LAST_DRAIN = "lastHungerDrainGameTime";
    private static final String GRAZE_COOLDOWN = "grazingCooldownUntil";
    private static final String TROUGH_COOLDOWN = "troughSearchCooldownUntil";
    private static final String GRASS_COOLDOWN = "grassSearchCooldownUntil";

    private AnimalHungerData() {
    }

    public static CompoundTag get(LivingEntity entity) {
        return entity.getPersistentData().getCompound(ROOT);
    }

    public static CompoundTag getOrCreate(LivingEntity entity, long gameTime) {
        CompoundTag root = entity.getPersistentData().getCompound(ROOT);
        if (!root.getBoolean(INITIALIZED)) {
            root.putBoolean(INITIALIZED, true);
            root.putInt(HUNGER, maxHunger());
            root.putLong(LAST_FED, gameTime);
            root.putLong(LAST_DRAIN, gameTime);
            root.putLong(GRAZE_COOLDOWN, 0L);
            root.putLong(TROUGH_COOLDOWN, 0L);
            root.putLong(GRASS_COOLDOWN, 0L);
            entity.getPersistentData().put(ROOT, root);
        }
        root.putInt(HUNGER, clamp(root.getInt(HUNGER)));
        return root;
    }

    public static int hunger(LivingEntity entity) {
        CompoundTag root = entity.getPersistentData().getCompound(ROOT);
        return root.getBoolean(INITIALIZED) ? clamp(root.getInt(HUNGER)) : maxHunger();
    }

    public static boolean isHungry(LivingEntity entity, int threshold) {
        return hunger(entity) <= Math.min(maxHunger() - 1, threshold);
    }

    public static int maxHunger() {
        return Math.max(1, AnimalHungerConfig.MAX_HUNGER.get());
    }

    public static int addHunger(LivingEntity entity, int amount, long gameTime) {
        CompoundTag root = getOrCreate(entity, gameTime);
        int before = clamp(root.getInt(HUNGER));
        int after = clamp(before + Math.max(0, amount));
        root.putInt(HUNGER, after);
        root.putLong(LAST_FED, gameTime);
        root.putLong(LAST_DRAIN, gameTime);
        entity.getPersistentData().put(ROOT, root);
        return after - before;
    }

    public static void drainCatchUp(LivingEntity entity, long gameTime) {
        CompoundTag root = getOrCreate(entity, gameTime);
        int hunger = clamp(root.getInt(HUNGER));
        if (hunger <= 0) {
            return;
        }
        long interval = AnimalHungerConfig.hungerPointIntervalTicks();
        long lastDrain = root.getLong(LAST_DRAIN);
        if (lastDrain <= 0L || gameTime < lastDrain) {
            root.putLong(LAST_DRAIN, gameTime);
            return;
        }
        long points = (gameTime - lastDrain) / interval;
        if (points <= 0L) {
            return;
        }
        int drained = (int) Math.min(points, hunger);
        root.putInt(HUNGER, hunger - drained);
        root.putLong(LAST_DRAIN, lastDrain + points * interval);
        entity.getPersistentData().put(ROOT, root);
    }

    public static boolean cooldownReady(LivingEntity entity, String key, long gameTime) {
        return getOrCreate(entity, gameTime).getLong(key) <= gameTime;
    }

    public static void setCooldown(LivingEntity entity, String key, long untilGameTime) {
        CompoundTag root = getOrCreate(entity, untilGameTime);
        root.putLong(key, untilGameTime);
        entity.getPersistentData().put(ROOT, root);
    }

    public static boolean troughSearchReady(LivingEntity entity, long gameTime) {
        return cooldownReady(entity, TROUGH_COOLDOWN, gameTime);
    }

    public static void setTroughSearchCooldown(LivingEntity entity, long untilGameTime) {
        setCooldown(entity, TROUGH_COOLDOWN, untilGameTime);
    }

    public static boolean grassSearchReady(LivingEntity entity, long gameTime) {
        return cooldownReady(entity, GRASS_COOLDOWN, gameTime);
    }

    public static void setGrassSearchCooldown(LivingEntity entity, long untilGameTime) {
        setCooldown(entity, GRASS_COOLDOWN, untilGameTime);
    }

    public static boolean grazingReady(LivingEntity entity, long gameTime) {
        return cooldownReady(entity, GRAZE_COOLDOWN, gameTime);
    }

    public static void setGrazingCooldown(LivingEntity entity, long untilGameTime) {
        setCooldown(entity, GRAZE_COOLDOWN, untilGameTime);
    }

    private static int clamp(int hunger) {
        return Math.max(0, Math.min(maxHunger(), hunger));
    }
}
