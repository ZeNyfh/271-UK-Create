package git.zenyfh.vanilla_adjustments.mixin;

import git.zenyfh.vanilla_adjustments.PlayerPlacedWaterSourceHandler;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.WaterFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WaterFluid.class)
public abstract class WaterFluidMixin {
    @Inject(method = "canConvertToSource", at = @At("HEAD"), cancellable = true)
    private void vanillaadjust$blockContraptionSourceConversion(Level level, CallbackInfoReturnable<Boolean> callbackInfo) {
        if (PlayerPlacedWaterSourceHandler.shouldSuppressContraptionWaterSourceConversion(level)) {
            callbackInfo.setReturnValue(false);
        }
    }
}
