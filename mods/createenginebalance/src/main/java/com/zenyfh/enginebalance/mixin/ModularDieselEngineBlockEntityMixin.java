package com.zenyfh.enginebalance.mixin;

import com.zenyfh.enginebalance.EngineBalanceConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(targets = "com.jesz.createdieselgenerators.content.diesel_engine.modular.ModularDieselEngineBlockEntity")
public abstract class ModularDieselEngineBlockEntityMixin {
    @ModifyArg(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/fluids/capability/templates/FluidTank;drain(ILnet/neoforged/neoforge/fluids/capability/IFluidHandler$FluidAction;)Lnet/neoforged/neoforge/fluids/FluidStack;"
            ),
            index = 0
    )
    private int enginebalance$scaleModularDieselEngineDrain(int amount) {
        // The amount is the assembled modular engine length; scale only this controller fuel draw.
        return EngineBalanceConfig.scaleFixedDieselDrain(amount);
    }
}
