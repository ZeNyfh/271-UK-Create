package com.zenyfh.enginebalance.mixin;

import com.zenyfh.enginebalance.ContraptionActorPower;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(
        targets = {
                "com.simibubi.create.content.contraptions.actors.harvester.HarvesterMovementBehaviour",
                "com.simibubi.create.content.contraptions.actors.plough.PloughMovementBehaviour",
                "com.simibubi.create.content.contraptions.actors.roller.RollerMovementBehaviour"
        },
        remap = false
)
public abstract class FreeContraptionActorVisitMixin {
    @Inject(
            method = "visitNewPosition(Lcom/simibubi/create/content/contraptions/behaviour/MovementContext;Lnet/minecraft/core/BlockPos;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void enginebalance$throttleVisit(@Coerce Object context, @Coerce Object pos, CallbackInfo ci) {
        if (ContraptionActorPower.shouldThrottleSkip(context)) {
            ci.cancel();
        }
    }
}
