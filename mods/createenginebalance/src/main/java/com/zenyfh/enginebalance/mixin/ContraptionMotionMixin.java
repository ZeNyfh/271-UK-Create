package com.zenyfh.enginebalance.mixin;

import com.zenyfh.enginebalance.ContraptionActorPower;
import com.zenyfh.enginebalance.EngineBalanceConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Scales free MountedContraption / CarriageContraption entity motion by cumulative actor load.
 */
@Mixin(targets = "com.simibubi.create.content.contraptions.AbstractContraptionEntity", remap = false)
public abstract class ContraptionMotionMixin {
    @Inject(
            method = "setContraptionMotion(Lnet/minecraft/world/phys/Vec3;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void enginebalance$scaleMotion(@Coerce Object motion, CallbackInfo ci) {
        if (motion == null) {
            return;
        }
        Object contraption = null;
        try {
            Field contraptionField = findContraptionField(this.getClass());
            if (contraptionField != null) {
                contraption = contraptionField.get(this);
            }
        } catch (ReflectiveOperationException ignored) {
        }
        if (contraption == null) {
            try {
                Method getContraption = this.getClass().getMethod("getContraption");
                contraption = getContraption.invoke(this);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        if (!EngineBalanceConfig.shouldNerfFreeContraptionActors(contraption)) {
            return;
        }
        double scale = ContraptionActorPower.machineLoadScaleForContraption(contraption);
        if (scale >= 1.0D) {
            return;
        }
        try {
            Object scaled = motion.getClass().getMethod("scale", double.class).invoke(motion, scale);
            Class<?> vec3Class = Class.forName("net.minecraft.world.phys.Vec3");
            this.getClass().getMethod("setDeltaMovement", vec3Class).invoke(this, scaled);
            ci.cancel();
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static Field findContraptionField(Class<?> type) {
        Class<?> cursor = type;
        while (cursor != null && cursor != Object.class) {
            try {
                Field field = cursor.getDeclaredField("contraption");
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        return null;
    }
}
