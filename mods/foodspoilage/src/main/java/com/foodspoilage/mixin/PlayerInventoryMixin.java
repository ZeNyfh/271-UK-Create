package com.foodspoilage.mixin;

import com.foodspoilage.spoilage.InventorySpoilageHooks;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Inventory.class)
public abstract class PlayerInventoryMixin {
    @Shadow @Final public Player player;

    @Inject(method = "setItem", at = @At("HEAD"))
    private void foodspoilage$startSpoilageWhenSetInPlayerInventory(int slot, ItemStack stack, CallbackInfo callback) {
        if (this.player != null && !this.player.level().isClientSide()) {
            InventorySpoilageHooks.onStackEnteredInventory(stack);
        }
    }
}
