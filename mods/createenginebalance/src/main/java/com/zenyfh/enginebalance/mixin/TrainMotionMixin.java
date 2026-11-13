package com.zenyfh.enginebalance.mixin;

import com.zenyfh.enginebalance.ContraptionActorPower;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Caps train speed by cumulative free-actor machine load so many drills/saws can stall a train.
 */
@Mixin(targets = "com.simibubi.create.content.trains.entity.Train", remap = false)
public abstract class TrainMotionMixin {
    @Inject(method = "approachTargetSpeed(F)V", at = @At("RETURN"), remap = false)
    private void enginebalance$capSpeedForActorLoad(float partial, CallbackInfo ci) {
        double scale = ContraptionActorPower.machineLoadScaleForTrain(this);
        if (scale >= 1.0D) {
            return;
        }
        try {
            Field speedField = this.getClass().getField("speed");
            Field throttleField = this.getClass().getField("throttle");
            Method maxSpeedMethod = this.getClass().getMethod("maxSpeed");

            double speed = ((Number) speedField.get(this)).doubleValue();
            double throttle = ((Number) throttleField.get(this)).doubleValue();
            float maxSp = ((Number) maxSpeedMethod.invoke(this)).floatValue();

            double limit = Math.abs(maxSp) * Math.abs(throttle) * scale;
            if (Math.abs(speed) > limit) {
                speedField.set(this, Math.copySign(limit, speed));
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
