package com.foodspoilage.mixin;

import com.foodspoilage.spoilage.InventorySpoilageHooks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BrewingStandBlockEntity.class)
public abstract class BrewingStandBlockEntityMixin {
    @Inject(method = "setItem", at = @At("HEAD"))
    private void foodspoilage$startSpoilageWhenSetInBrewingStandInventory(int slot, ItemStack stack, CallbackInfo callback) {
        Level level = ((BlockEntity) (Object) this).getLevel();
        if (level != null && !level.isClientSide()) {
            InventorySpoilageHooks.onStackEnteredInventory(stack);
        }
    }
}
