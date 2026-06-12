package git.zenyfh.vanilla_adjustments.mixin;

import git.zenyfh.vanilla_adjustments.PlayerPlacedWaterSourceHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BucketItem.class)
public abstract class BucketItemMixin {
    @Unique
    private static final ThreadLocal<Boolean> VANILLAADJUST$TARGET_WAS_SOURCE_WATER = ThreadLocal.withInitial(() -> false);

    @Shadow
    @Final
    public Fluid content;

    @Inject(
            method = "emptyContents(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/BlockHitResult;Lnet/minecraft/world/item/ItemStack;)Z",
            at = @At("HEAD")
    )
    private void vanillaadjust$captureExistingWaterSource(Player player, Level level, BlockPos pos, BlockHitResult hitResult, ItemStack container, CallbackInfoReturnable<Boolean> callbackInfo) {
        VANILLAADJUST$TARGET_WAS_SOURCE_WATER.set(level.getFluidState(pos).is(FluidTags.WATER) && level.getFluidState(pos).isSource());
    }

    @Inject(
            method = "emptyContents(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/BlockHitResult;Lnet/minecraft/world/item/ItemStack;)Z",
            at = @At("RETURN")
    )
    private void vanillaadjust$markPlayerPlacedWaterSource(Player player, Level level, BlockPos pos, BlockHitResult hitResult, ItemStack container, CallbackInfoReturnable<Boolean> callbackInfo) {
        try {
            if (callbackInfo.getReturnValueZ() && this.content.is(FluidTags.WATER) && !VANILLAADJUST$TARGET_WAS_SOURCE_WATER.get()) {
                PlayerPlacedWaterSourceHandler.markPlayerPlacedWaterSource(player, level, pos);
            }
        } finally {
            VANILLAADJUST$TARGET_WAS_SOURCE_WATER.remove();
        }
    }
}
