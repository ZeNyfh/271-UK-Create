package com.foodspoilage.recipe;

import com.foodspoilage.FoodSpoilage;
import com.foodspoilage.config.FoodSpoilageConfig;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

public final class RecipeCache {
    private static final RecipeCache INSTANCE = new RecipeCache();

    private final Map<Item, List<RecipeNode>> producers = new Object2ObjectOpenHashMap<>();
    private final Set<Item> rawFoods = new HashSet<>();
    private final Set<Item> cookedFoods = new HashSet<>();
    private volatile boolean built;

    public static RecipeCache get() {
        return INSTANCE;
    }

    public synchronized void rebuild(RecipeManager recipeManager, HolderLookup.Provider registries) {
        this.producers.clear();
        this.rawFoods.clear();
        this.cookedFoods.clear();

        Collection<RecipeHolder<?>> recipes = recipeManager.getRecipes();
        for (RecipeHolder<?> holder : recipes) {
            Recipe<?> recipe = holder.value();
            ItemStack result = safeResult(recipe, registries);
            if (result.isEmpty()) {
                continue;
            }

            Item resultItem = result.getItem();
            List<List<Item>> ingredients = resolveIngredients(recipe.getIngredients());
            boolean cooking = isCookingType(recipe.getType());

            RecipeNode node = new RecipeNode(resultItem, ingredients, cooking, recipe.getIngredients().size(), holder.id().toString());
            this.producers.computeIfAbsent(resultItem, ignored -> new ArrayList<>()).add(node);

            if (cooking && result.has(DataComponents.FOOD)) {
                this.cookedFoods.add(resultItem);
                for (List<Item> alternatives : ingredients) {
                    for (Item input : alternatives) {
                        if (input.components().has(DataComponents.FOOD)) {
                            this.rawFoods.add(input);
                        }
                    }
                }
            }
        }

        this.built = true;
        if (FoodSpoilageConfig.DEBUG_LOGGING.get()) {
            FoodSpoilage.LOGGER.info("Analyzed {} recipes; {} foods have producers, {} raw foods, {} cooked foods",
                recipes.size(), this.producers.size(), this.rawFoods.size(), this.cookedFoods.size());
        }
    }

    public List<RecipeNode> producersFor(Item item) {
        return this.producers.getOrDefault(item, List.of());
    }

    public boolean isRawFood(Item item) {
        return this.rawFoods.contains(item);
    }

    public boolean isCookedFood(Item item) {
        return this.cookedFoods.contains(item);
    }

    public boolean isBuilt() {
        return this.built;
    }

    public void clear() {
        this.producers.clear();
        this.rawFoods.clear();
        this.cookedFoods.clear();
        this.built = false;
    }

    private static boolean isCookingType(RecipeType<?> type) {
        return type == RecipeType.SMELTING
            || type == RecipeType.SMOKING
            || type == RecipeType.CAMPFIRE_COOKING
            || type == RecipeType.BLASTING;
    }

    private static ItemStack safeResult(Recipe<?> recipe, HolderLookup.Provider registries) {
        try {
            return recipe.getResultItem(registries);
        } catch (RuntimeException exception) {
            if (FoodSpoilageConfig.DEBUG_LOGGING.get()) {
                FoodSpoilage.LOGGER.debug("Could not inspect recipe result for {}", recipe, exception);
            }
            return ItemStack.EMPTY;
        }
    }

    private static List<List<Item>> resolveIngredients(List<Ingredient> ingredients) {
        List<List<Item>> result = new ArrayList<>(ingredients.size());
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) {
                continue;
            }
            ItemStack[] stacks = ingredient.getItems();
            if (stacks.length == 0) {
                continue;
            }
            Set<Item> alternatives = new HashSet<>();
            for (ItemStack stack : stacks) {
                if (!stack.isEmpty()) {
                    alternatives.add(stack.getItem());
                }
            }
            if (!alternatives.isEmpty()) {
                result.add(List.copyOf(alternatives));
            }
        }
        return Collections.unmodifiableList(result);
    }

    public record RecipeNode(Item output, List<List<Item>> ingredientAlternatives, boolean cooking, int ingredientSlots, String id) {
        public int distinctIngredientCount() {
            Set<Item> distinct = new HashSet<>();
            for (List<Item> alternatives : this.ingredientAlternatives) {
                distinct.addAll(alternatives);
            }
            return distinct.size();
        }
    }
}
