package com.foodspoilage.mixin;

import com.foodspoilage.config.FoodSpoilageConfig;
import com.foodspoilage.registry.ModDataComponents;
import com.foodspoilage.spoilage.FoodClassificationManager;
import com.foodspoilage.spoilage.SpoilageManager;
import com.foodspoilage.spoilage.SpoilageMergeTracker;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    @Inject(method = "isSameItemSameComponents", at = @At("HEAD"), cancellable = true)
    private static void foodspoilage$ignoreSpoilageComponent(ItemStack first, ItemStack second, CallbackInfoReturnable<Boolean> callback) {
        if (!ItemStack.isSameItem(first, second)) {
            return;
        }
        if (!first.has(ModDataComponents.FOOD_STACK_DATA) && !second.has(ModDataComponents.FOOD_STACK_DATA)) {
            return;
        }
        ItemStack cleanFirst = first.copy();
        ItemStack cleanSecond = second.copy();
        cleanFirst.remove(ModDataComponents.FOOD_STACK_DATA);
        cleanSecond.remove(ModDataComponents.FOOD_STACK_DATA);
        boolean matches = cleanFirst.getComponents().equals(cleanSecond.getComponents());
        if (matches) {
            SpoilageMergeTracker.record(first, second);
        }
        callback.setReturnValue(matches);
    }

    @Inject(method = "grow", at = @At("HEAD"))
    private void foodspoilage$averageFreshnessOnGrow(int increment, CallbackInfo callback) {
        SpoilageMergeTracker.tryApplyGrow((ItemStack) (Object) this, increment);
    }

    @Inject(method = "hashItemAndComponents", at = @At("HEAD"), cancellable = true)
    private static void foodspoilage$ignoreSpoilageInHash(ItemStack stack, CallbackInfoReturnable<Integer> callback) {
        if (stack != null && stack.has(ModDataComponents.FOOD_STACK_DATA)) {
            ItemStack clean = stack.copy();
            clean.remove(ModDataComponents.FOOD_STACK_DATA);
            callback.setReturnValue(ItemStack.hashItemAndComponents(clean));
        }
    }

    @Inject(method = "isBarVisible", at = @At("HEAD"), cancellable = true)
    private void foodspoilage$showFreshnessBar(CallbackInfoReturnable<Boolean> callback) {
        ItemStack stack = (ItemStack) (Object) this;
        if (FoodSpoilageConfig.DURABILITY_BAR.get() && stack.has(ModDataComponents.FOOD_STACK_DATA) && FoodClassificationManager.isSpoilageEligible(stack) && !stack.isDamageableItem()) {
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "getBarWidth", at = @At("HEAD"), cancellable = true)
    private void foodspoilage$freshnessBarWidth(CallbackInfoReturnable<Integer> callback) {
        ItemStack stack = (ItemStack) (Object) this;
        if (FoodSpoilageConfig.DURABILITY_BAR.get() && stack.has(ModDataComponents.FOOD_STACK_DATA) && FoodClassificationManager.isSpoilageEligible(stack) && !stack.isDamageableItem()) {
            callback.setReturnValue(SpoilageManager.barWidth(stack));
        }
    }

    @Inject(method = "getBarColor", at = @At("HEAD"), cancellable = true)
    private void foodspoilage$freshnessBarColor(CallbackInfoReturnable<Integer> callback) {
        ItemStack stack = (ItemStack) (Object) this;
        if (FoodSpoilageConfig.DURABILITY_BAR.get() && stack.has(ModDataComponents.FOOD_STACK_DATA) && FoodClassificationManager.isSpoilageEligible(stack) && !stack.isDamageableItem()) {
            callback.setReturnValue(SpoilageManager.barColor(stack));
        }
    }
}
