package git.zenyfh.vanilla_adjustments.mixin;

import git.zenyfh.vanilla_adjustments.WildWolfTargetPlayerGoal;
import net.minecraft.world.entity.animal.Wolf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Wolf.class)
public abstract class WolfMixin {
    @Inject(method = "registerGoals", at = @At("TAIL"))
    private void vanillaadjust$targetPlayersWhenWild(CallbackInfo callbackInfo) {
        Wolf wolf = (Wolf)(Object)this;
        wolf.targetSelector.addGoal(4, new WildWolfTargetPlayerGoal(wolf));
    }
}
