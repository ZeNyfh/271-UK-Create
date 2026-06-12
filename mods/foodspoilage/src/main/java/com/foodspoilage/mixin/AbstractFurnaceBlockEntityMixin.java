package com.foodspoilage.mixin;

import com.foodspoilage.spoilage.InventorySpoilageHooks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class AbstractFurnaceBlockEntityMixin {
    @Inject(method = "setItem", at = @At("HEAD"))
    private void foodspoilage$startSpoilageWhenSetInFurnaceInventory(int slot, ItemStack stack, CallbackInfo callback) {
        Level level = ((BlockEntity) (Object) this).getLevel();
        if (level != null && !level.isClientSide()) {
            InventorySpoilageHooks.onStackEnteredInventory(stack);
        }
    }
}
