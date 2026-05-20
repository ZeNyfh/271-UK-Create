package com.foodspoilage.config;

import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class FoodSpoilageConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.DoubleValue GLOBAL_SPOILAGE_SPEED;
    public static final ModConfigSpec.DoubleValue RAW_FOOD_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue COOKED_FOOD_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue PREPARED_FOOD_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue COMPLEXITY_PRESERVATION_MULTIPLIER;
    public static final ModConfigSpec.IntValue RECURSIVE_ANALYSIS_DEPTH;
    public static final ModConfigSpec.DoubleValue REFRIGERATION_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue BIOME_TEMPERATURE_MULTIPLIER;
    public static final ModConfigSpec.BooleanValue HUNGER_EFFECT;
    public static final ModConfigSpec.BooleanValue NAUSEA_EFFECT;
    public static final ModConfigSpec.BooleanValue POISON_EFFECT;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> WHITELIST;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BLACKLIST;
    public static final ModConfigSpec.BooleanValue DURABILITY_BAR;
    public static final ModConfigSpec.BooleanValue TOOLTIP;
    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING;

    public static final ModConfigSpec.DoubleValue BASE_SPOILAGE_DAYS;
    public static final ModConfigSpec.IntValue PREPARED_COMPLEXITY_THRESHOLD;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("timing");
        GLOBAL_SPOILAGE_SPEED = builder.comment("Higher values make all foods spoil faster. Default is 4x faster than the original balance.").defineInRange("globalSpoilageSpeed", 4.0D, 0.01D, 100.0D);
        BASE_SPOILAGE_DAYS = builder.comment("Baseline spoilage duration for simple neutral foods, in real-time days.").defineInRange("baseSpoilageDays", 2.0D, 0.02D, 365.0D);
        RAW_FOOD_MULTIPLIER = builder.defineInRange("rawFoodMultiplier", 0.35D, 0.01D, 10.0D);
        COOKED_FOOD_MULTIPLIER = builder.defineInRange("cookedFoodMultiplier", 0.85D, 0.01D, 10.0D);
        PREPARED_FOOD_MULTIPLIER = builder.defineInRange("preparedFoodMultiplier", 1.35D, 0.01D, 20.0D);
        COMPLEXITY_PRESERVATION_MULTIPLIER = builder.defineInRange("recipeComplexityPreservationMultiplier", 0.18D, 0.0D, 5.0D);
        REFRIGERATION_MULTIPLIER = builder.comment("Reserved for future refrigeration blocks. Values below 1 slow spoilage when refrigeration is active.").defineInRange("refrigerationMultiplier", 0.35D, 0.01D, 1.0D);
        BIOME_TEMPERATURE_MULTIPLIER = builder.comment("Reserved for future biome temperature systems.").defineInRange("biomeTemperatureMultiplier", 1.0D, 0.01D, 10.0D);
        builder.pop();

        builder.push("recipeAnalysis");
        RECURSIVE_ANALYSIS_DEPTH = builder.defineInRange("recursiveAnalysisDepth", 5, 0, 16);
        PREPARED_COMPLEXITY_THRESHOLD = builder.defineInRange("preparedComplexityThreshold", 5, 1, 100);
        builder.pop();

        builder.push("effects");
        HUNGER_EFFECT = builder.define("hunger", true);
        NAUSEA_EFFECT = builder.define("nausea", true);
        POISON_EFFECT = builder.define("poison", false);
        builder.pop();

        builder.push("compatibility");
        WHITELIST = builder.comment("Optional item ids. Empty means every item with a food component is eligible.").defineListAllowEmpty("whitelist", List.of(), () -> "", FoodSpoilageConfig::isString);
        BLACKLIST = builder.comment("Item ids excluded from spoilage.").defineListAllowEmpty("blacklist", List.of(), () -> "", FoodSpoilageConfig::isString);
        builder.pop();

        builder.push("client");
        DURABILITY_BAR = builder.define("durabilityBar", true);
        TOOLTIP = builder.define("tooltip", true);
        builder.pop();

        DEBUG_LOGGING = builder.define("debugLogging", false);
        SPEC = builder.build();
    }

    private FoodSpoilageConfig() {
    }

    private static boolean isString(Object value) {
        return value instanceof String;
    }
}
