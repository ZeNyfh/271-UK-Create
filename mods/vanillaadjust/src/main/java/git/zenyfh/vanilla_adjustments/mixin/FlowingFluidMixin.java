package git.zenyfh.vanilla_adjustments.mixin;

import git.zenyfh.vanilla_adjustments.PlayerPlacedWaterSourceHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
    @Inject(method = "getNewLiquid", at = @At("RETURN"), cancellable = true)
    private void vanillaadjust$blockArtificialWaterSourceConversion(Level level, BlockPos pos, BlockState state, CallbackInfoReturnable<FluidState> callbackInfo) {
        FluidState result = callbackInfo.getReturnValue();
        if (!result.is(FluidTags.WATER) || !result.isSource() || state.getFluidState().isSource()) {
            return;
        }
        if (PlayerPlacedWaterSourceHandler.shouldBlockWaterSourceConversion(level, pos)) {
            callbackInfo.setReturnValue(((FlowingFluid)(Object)this).getFlowing(7, false));
        }
    }
}
