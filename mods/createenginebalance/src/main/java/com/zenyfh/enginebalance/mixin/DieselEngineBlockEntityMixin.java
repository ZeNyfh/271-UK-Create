package com.zenyfh.enginebalance.mixin;

import com.zenyfh.enginebalance.EngineBalanceConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(targets = "com.jesz.createdieselgenerators.content.diesel_engine.normal.DieselEngineBlockEntity")
public abstract class DieselEngineBlockEntityMixin {
    @ModifyArg(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/foundation/fluid/SmartFluidTank;drain(ILnet/neoforged/neoforge/fluids/capability/IFluidHandler$FluidAction;)Lnet/neoforged/neoforge/fluids/FluidStack;"
            ),
            index = 0
    )
    private int enginebalance$scaleFixedDieselEngineDrain(int amount) {
        return EngineBalanceConfig.scaleFixedDieselDrain(amount);
    }
}
