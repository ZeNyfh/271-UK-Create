package git.zenyfh.vanilla_adjustments.mixin;

import git.zenyfh.vanilla_adjustments.DisableDripstoneLavaHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PointedDripstoneBlock.class)
public abstract class PointedDripstoneBlockMixin {
    @Inject(method = "getCauldronFillFluidType", at = @At("RETURN"), cancellable = true)
    private static void vanillaadjust$disableDripstoneCauldronFluid(ServerLevel level, BlockPos pos, CallbackInfoReturnable<Fluid> callbackInfo) {
        Fluid fluid = callbackInfo.getReturnValue();
        if (DisableDripstoneLavaHandler.shouldBlockDripstoneCauldronFill(level, pos, fluid)) {
            callbackInfo.setReturnValue(Fluids.EMPTY);
        }
    }
}
