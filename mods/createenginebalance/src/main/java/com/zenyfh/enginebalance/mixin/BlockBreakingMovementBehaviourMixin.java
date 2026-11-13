package com.zenyfh.enginebalance.mixin;

import com.zenyfh.enginebalance.ContraptionActorPower;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(
        targets = {
                "com.simibubi.create.content.kinetics.base.BlockBreakingMovementBehaviour",
                "com.simibubi.create.content.contraptions.actors.roller.RollerMovementBehaviour"
        },
        remap = false
)
public abstract class BlockBreakingMovementBehaviourMixin {
    @Inject(
            method = "getBlockBreakingSpeed(Lcom/simibubi/create/content/contraptions/behaviour/MovementContext;)F",
            at = @At("RETURN"),
            cancellable = true
    )
    private void enginebalance$nerfBreakingSpeed(@Coerce Object context, CallbackInfoReturnable<Float> cir) {
        float factor = ContraptionActorPower.speedFactor(context);
        if (factor < 1f) {
            cir.setReturnValue(cir.getReturnValueF() * factor);
        }
    }
}
