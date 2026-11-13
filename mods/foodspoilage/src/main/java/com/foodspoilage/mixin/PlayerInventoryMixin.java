package com.foodspoilage.mixin;

import com.foodspoilage.spoilage.FoodClassificationManager;
import com.foodspoilage.spoilage.InventorySpoilageHooks;
import com.foodspoilage.spoilage.SpoilageManager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Inventory.class)
public abstract class PlayerInventoryMixin {
    @Shadow @Final public Player player;

    @ModifyVariable(method = "setItem", at = @At("HEAD"), argsOnly = true)
    private ItemStack foodspoilage$startSpoilageWhenSetInPlayerInventory(ItemStack stack) {
        if (this.player == null || this.player.level().isClientSide()) {
            return stack;
        }
        if (this.player.getAbilities().instabuild
                && FoodClassificationManager.isSpoilageEligible(stack)
                && SpoilageManager.existingData(stack) == null) {
            ItemStack copy = stack.copy();
            InventorySpoilageHooks.onStackEnteredInventory(copy);
            return copy;
        }
        InventorySpoilageHooks.onStackEnteredInventory(stack);
        return stack;
    }
}
