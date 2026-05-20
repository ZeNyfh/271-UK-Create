package com.foodspoilage.spoilage;

import com.foodspoilage.config.FoodSpoilageConfig;
import com.foodspoilage.recipe.RecipeCache;
import com.foodspoilage.recipe.RecipeComplexityManager;
import com.foodspoilage.registry.ModItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class FoodClassificationManager {
    private static final long DAY_MILLIS = 86_400_000L;
    private static final long MIN_DURATION_MILLIS = 300_000L;

    private FoodClassificationManager() {
    }

    public static boolean isSpoilageEligible(ItemStack stack) {
        if (stack.isEmpty() || !stack.has(DataComponents.FOOD)) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String key = id.toString();
        if (FoodSpoilageConfig.BLACKLIST.get().contains(key)) {
            return false;
        }
        return FoodSpoilageConfig.WHITELIST.get().isEmpty() || FoodSpoilageConfig.WHITELIST.get().contains(key);
    }

    public static FoodProfile profile(ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food == null) {
            return new FoodProfile(FoodClassification.SIMPLE, 0.0D, DAY_MILLIS);
        }
        Item item = stack.getItem();
        double complexity = RecipeComplexityManager.get().complexity(item, FoodSpoilageConfig.RECURSIVE_ANALYSIS_DEPTH.get());
        FoodClassification classification = classify(item, complexity);

        double nutritionPressure = 1.0D + food.nutrition() * 0.07D + food.saturation() * 0.09D;
        double multiplier = switch (classification) {
            case RAW -> FoodSpoilageConfig.RAW_FOOD_MULTIPLIER.get();
            case COOKED -> FoodSpoilageConfig.COOKED_FOOD_MULTIPLIER.get();
            case PREPARED -> FoodSpoilageConfig.PREPARED_FOOD_MULTIPLIER.get();
            case SPOILED_VARIANT -> 0.20D;
            case SIMPLE -> 1.0D;
        };
        double preservation = 1.0D + complexity * FoodSpoilageConfig.COMPLEXITY_PRESERVATION_MULTIPLIER.get();
        double base = FoodSpoilageConfig.BASE_SPOILAGE_DAYS.get() * DAY_MILLIS;
        double duration = (base * multiplier * preservation) / Math.max(0.01D, nutritionPressure * FoodSpoilageConfig.GLOBAL_SPOILAGE_SPEED.get());
        return new FoodProfile(classification, complexity, Math.max(MIN_DURATION_MILLIS, Math.round(duration)));
    }

    public static FoodClassification classify(Item item, double complexity) {
        if (item == ModItems.SPOILED_RAW_FOOD.get() || item == ModItems.SPOILED_COOKED_FOOD.get()) {
            return FoodClassification.SPOILED_VARIANT;
        }
        RecipeCache cache = RecipeCache.get();
        if (cache.isRawFood(item)) {
            return FoodClassification.RAW;
        }
        if (complexity >= FoodSpoilageConfig.PREPARED_COMPLEXITY_THRESHOLD.get()) {
            return FoodClassification.PREPARED;
        }
        if (cache.isCookedFood(item)) {
            return FoodClassification.COOKED;
        }
        return FoodClassification.SIMPLE;
    }
}
