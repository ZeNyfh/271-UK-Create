package com.foodspoilage.spoilage;

import com.foodspoilage.registry.ModDataComponents;
import com.foodspoilage.registry.ModItems;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class SpoilageManager {
    private SpoilageManager() {
    }

    public static long now() {
        return System.currentTimeMillis();
    }

    public static ItemStack ensureInitialized(ItemStack stack) {
        if (!FoodClassificationManager.isSpoilageEligible(stack)) {
            return stack;
        }
        FoodStackData data = stack.get(ModDataComponents.FOOD_STACK_DATA);
        long now = now();
        FoodProfile profile = FoodClassificationManager.profile(stack);
        if (data == null || data.durationMillis() <= 0L) {
            stack.set(ModDataComponents.FOOD_STACK_DATA, FoodStackData.create(now, profile));
        }
        return stack;
    }

    public static FoodStackData data(ItemStack stack) {
        ensureInitialized(stack);
        return stack.get(ModDataComponents.FOOD_STACK_DATA);
    }

    public static FoodStackData existingData(ItemStack stack) {
        return stack.get(ModDataComponents.FOOD_STACK_DATA);
    }

    public static SpoilageStage stage(ItemStack stack) {
        FoodStackData data = data(stack);
        return data == null ? SpoilageStage.FRESH : data.stageAt(now());
    }

    public static double freshness(ItemStack stack) {
        FoodStackData data = data(stack);
        return data == null ? 1.0D : data.freshnessAt(now());
    }

    public static void averageInto(ItemStack target, ItemStack incoming, int incomingCount) {
        if (target.isEmpty() || incoming.isEmpty() || incomingCount <= 0) {
            return;
        }
        if (!FoodClassificationManager.isSpoilageEligible(target) || !FoodClassificationManager.isSpoilageEligible(incoming)) {
            return;
        }
        long now = now();
        FoodProfile profile = FoodClassificationManager.profile(target);
        FoodStackData targetData = data(target);
        FoodStackData incomingData = data(incoming);
        if (targetData == null || incomingData == null) {
            return;
        }
        target.set(ModDataComponents.FOOD_STACK_DATA, targetData.averagedWith(incomingData, target.getCount(), incomingCount, now, profile));
    }

    public static void refresh(ItemStack stack) {
        if (!FoodClassificationManager.isSpoilageEligible(stack)) {
            return;
        }
        FoodStackData data = stack.get(ModDataComponents.FOOD_STACK_DATA);
        if (data != null) {
            stack.set(ModDataComponents.FOOD_STACK_DATA, data.refreshed(now()));
        } else {
            ensureInitialized(stack);
        }
    }


    public static void refreshPreserved(ItemStack stack, double preservationMultiplier) {
        if (!FoodClassificationManager.isSpoilageEligible(stack)) {
            return;
        }
        FoodStackData data = data(stack);
        if (data == null) {
            return;
        }
        FoodProfile profile = FoodClassificationManager.profile(stack);
        long duration = Math.max(1_000L, Math.round(profile.durationMillis() * Math.max(1.0D, preservationMultiplier)));
        setDurationPreservingFreshness(stack, data, profile, duration);
    }

    public static void restoreBaseDuration(ItemStack stack) {
        if (!FoodClassificationManager.isSpoilageEligible(stack)) {
            return;
        }
        FoodStackData data = stack.get(ModDataComponents.FOOD_STACK_DATA);
        if (data == null) {
            ensureInitialized(stack);
            return;
        }
        FoodProfile profile = FoodClassificationManager.profile(stack);
        long duration = Math.max(1_000L, profile.durationMillis());
        setDurationPreservingFreshness(stack, data, profile, duration, true);
    }

    private static void setDurationPreservingFreshness(ItemStack stack, FoodStackData data, FoodProfile profile, long duration) {
        setDurationPreservingFreshness(stack, data, profile, duration, false);
    }

    private static void setDurationPreservingFreshness(ItemStack stack, FoodStackData data, FoodProfile profile, long duration, boolean roundRemainingDown) {
        long now = now();
        double freshness = clamp(data.freshnessAt(now));
        double scaledRemaining = duration * freshness;
        long remaining = Math.max(0L, roundRemainingDown ? (long) Math.floor(scaledRemaining) : Math.round(scaledRemaining));
        double storedFreshness = duration <= 0L ? 0.0D : clamp(remaining / (double) duration);
        stack.set(ModDataComponents.FOOD_STACK_DATA, new FoodStackData(
            data.creationTime(),
            now,
            now + remaining,
            duration,
            storedFreshness,
            profile.complexity(),
            profile.classification().name()
        ));
    }

    private static double clamp(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    public static ItemStack transformIfRotten(ItemStack stack) {
        if (!FoodClassificationManager.isSpoilageEligible(stack)) {
            return stack;
        }
        FoodStackData data = data(stack);
        if (data == null || data.stageAt(now()) != SpoilageStage.ROTTEN) {
            return stack;
        }

        int count = stack.getCount();
        ItemStack replacement;
        FoodClassification classification = FoodClassificationManager.profile(stack).classification();
        if (classification == FoodClassification.SPOILED_VARIANT) {
            replacement = new ItemStack(Items.ROTTEN_FLESH, count);
        } else if (classification == FoodClassification.RAW) {
            replacement = new ItemStack(ModItems.SPOILED_RAW_FOOD.get(), count);
        } else {
            replacement = new ItemStack(ModItems.SPOILED_COOKED_FOOD.get(), count);
        }
        ensureInitialized(replacement);
        return replacement;
    }

    public static int barWidth(ItemStack stack) {
        FoodStackData data = existingData(stack);
        double freshness = data == null ? 1.0D : data.freshnessAt(now());
        return Math.max(0, Math.min(13, (int) Math.round(freshness * 13.0D)));
    }

    public static int barColor(ItemStack stack) {
        FoodStackData data = existingData(stack);
        double freshness = data == null ? 1.0D : data.freshnessAt(now());
        if (freshness > 0.66D) {
            return 0x43C95A;
        }
        if (freshness > 0.33D) {
            return 0xD6C642;
        }
        if (freshness > 0.0D) {
            return 0xD66A2D;
        }
        return 0x8B1D1D;
    }
}
