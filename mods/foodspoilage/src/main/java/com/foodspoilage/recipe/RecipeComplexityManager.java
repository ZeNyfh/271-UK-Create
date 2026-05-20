package com.foodspoilage.recipe;

import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;

public final class RecipeComplexityManager {
    private static final RecipeComplexityManager INSTANCE = new RecipeComplexityManager();

    private final Object2DoubleOpenHashMap<Item> memoizedComplexity = new Object2DoubleOpenHashMap<>();

    private RecipeComplexityManager() {
        this.memoizedComplexity.defaultReturnValue(Double.NaN);
    }

    public static RecipeComplexityManager get() {
        return INSTANCE;
    }

    public synchronized void clear() {
        this.memoizedComplexity.clear();
    }

    public double complexity(Item item, int maxDepth) {
        return complexity(item, Math.max(0, maxDepth), new HashSet<>());
    }

    private double complexity(Item item, int remainingDepth, Set<Item> visiting) {
        double cached = this.memoizedComplexity.getDouble(item);
        if (!Double.isNaN(cached)) {
            return cached;
        }
        if (remainingDepth <= 0 || !visiting.add(item)) {
            return 0.0D;
        }

        double best = 0.0D;
        List<RecipeCache.RecipeNode> producers = RecipeCache.get().producersFor(item);
        for (RecipeCache.RecipeNode node : producers) {
            double score = scoreNode(node, remainingDepth, visiting);
            if (score > best) {
                best = score;
            }
        }

        visiting.remove(item);
        double clamped = Math.min(64.0D, best);
        this.memoizedComplexity.put(item, clamped);
        return clamped;
    }

    private double scoreNode(RecipeCache.RecipeNode node, int remainingDepth, Set<Item> visiting) {
        double score = Math.max(node.ingredientSlots(), node.ingredientAlternatives().size());
        score += Math.min(4, node.distinctIngredientCount()) * 0.35D;
        if (node.cooking()) {
            score += 1.25D;
        }

        for (List<Item> alternatives : node.ingredientAlternatives()) {
            double bestAlternative = 0.0D;
            for (Item item : alternatives) {
                double child = complexity(item, remainingDepth - 1, visiting);
                if (item.components().has(DataComponents.FOOD)) {
                    child += 0.75D;
                }
                bestAlternative = Math.max(bestAlternative, child);
            }
            score += bestAlternative * 0.55D;
        }

        return score;
    }
}
