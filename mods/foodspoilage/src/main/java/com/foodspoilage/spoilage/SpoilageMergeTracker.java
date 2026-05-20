package com.foodspoilage.spoilage;

import com.foodspoilage.registry.ModDataComponents;
import net.minecraft.world.item.ItemStack;

public final class SpoilageMergeTracker {
    private static final ThreadLocal<Candidate> LAST_CANDIDATE = new ThreadLocal<>();

    private SpoilageMergeTracker() {
    }

    public static void record(ItemStack first, ItemStack second) {
        if (first.has(ModDataComponents.FOOD_STACK_DATA) || second.has(ModDataComponents.FOOD_STACK_DATA)) {
            LAST_CANDIDATE.set(new Candidate(first, second));
        }
    }

    public static void tryApplyGrow(ItemStack target, int increment) {
        if (increment <= 0 || target.isEmpty()) {
            return;
        }
        Candidate candidate = LAST_CANDIDATE.get();
        if (candidate == null) {
            return;
        }
        ItemStack incoming = candidate.otherFor(target);
        LAST_CANDIDATE.remove();
        if (incoming != null && incoming.getItem() == target.getItem()) {
            SpoilageManager.averageInto(target, incoming, Math.min(increment, incoming.getCount()));
        }
    }

    private record Candidate(ItemStack first, ItemStack second) {
        ItemStack otherFor(ItemStack stack) {
            if (stack == first) {
                return second;
            }
            if (stack == second) {
                return first;
            }
            if (ItemStack.isSameItem(stack, first)) {
                return first;
            }
            if (ItemStack.isSameItem(stack, second)) {
                return second;
            }
            return null;
        }
    }
}
