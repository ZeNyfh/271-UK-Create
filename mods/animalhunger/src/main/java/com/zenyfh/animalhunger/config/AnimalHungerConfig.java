package com.zenyfh.animalhunger.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class AnimalHungerConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue HUNGER_ENABLED;
    public static final ModConfigSpec.IntValue MAX_HUNGER;
    public static final ModConfigSpec.DoubleValue STARVATION_DAYS;
    public static final ModConfigSpec.IntValue TROUGH_SEARCH_RADIUS;
    public static final ModConfigSpec.IntValue GRASS_SEARCH_RADIUS;
    public static final ModConfigSpec.IntValue GRAZING_RESTORES;
    public static final ModConfigSpec.IntValue GRAZING_COOLDOWN_TICKS;
    public static final ModConfigSpec.BooleanValue GRAZING_DAMAGES_GRASS_BLOCKS;
    public static final ModConfigSpec.BooleanValue ENABLE_JADE_INTEGRATION;
    public static final ModConfigSpec.BooleanValue ENABLE_WILDERNATURE_SUPPORT;
    public static final ModConfigSpec.BooleanValue DEBUG_ANIMAL_HUNGER;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("hunger");
        HUNGER_ENABLED = builder.comment("When true, supported passive animals lose hunger over time and can starve.").define("hungerEnabled", true);
        MAX_HUNGER = builder.comment("Maximum hunger value for animals.").defineInRange("maxHunger", 20, 1, 100);
        STARVATION_DAYS = builder.comment("Minecraft days for a full animal to starve without food. One day is 24000 ticks, so 1.5 days is about 30 real minutes.").defineInRange("starvationDays", 1.5D, 0.1D, 100.0D);
        builder.pop();

        builder.push("feeding");
        TROUGH_SEARCH_RADIUS = builder.defineInRange("troughSearchRadius", 24, 1, 64);
        GRASS_SEARCH_RADIUS = builder.defineInRange("grassSearchRadius", 16, 1, 48);
        GRAZING_RESTORES = builder.defineInRange("grazingRestores", 3, 1, 100);
        GRAZING_COOLDOWN_TICKS = builder.defineInRange("grazingCooldownTicks", 1200, 20, 24000);
        GRAZING_DAMAGES_GRASS_BLOCKS = builder.comment("When true, grazing grass blocks converts them to dirt. Short grass and ferns may still be consumed.").define("grazingDamagesGrassBlocks", false);
        builder.pop();

        builder.push("compatibility");
        ENABLE_JADE_INTEGRATION = builder.define("enableJadeIntegration", true);
        ENABLE_WILDERNATURE_SUPPORT = builder.define("enableWilderNatureSupport", true);
        builder.pop();

        DEBUG_ANIMAL_HUNGER = builder.define("debugAnimalHunger", false);
        SPEC = builder.build();
    }

    private AnimalHungerConfig() {
    }

    public static long hungerPointIntervalTicks() {
        double days = Math.max(0.1D, STARVATION_DAYS.get());
        int maxHunger = Math.max(1, MAX_HUNGER.get());
        return Math.max(1L, Math.round(days * 24000.0D / maxHunger));
    }
}
