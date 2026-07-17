package com.ukgeo.realtimelocalisedweather.mixin;

import com.ukgeo.realtimelocalisedweather.RealtimeLocalisedWeatherMod;
import com.ukgeo.realtimelocalisedweather.weather.WeatherAuthorityMode;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelWeatherMixin {
    @Inject(method = "isRainingAt", at = @At("HEAD"), cancellable = true)
    private void realtime_localised_weather$isRainingAt(BlockPos pos, CallbackInfoReturnable<Boolean> callbackInfo) {
        if ((Object) this instanceof ServerLevel serverLevel) {
            callbackInfo.setReturnValue(RealtimeLocalisedWeatherMod.serverWeatherManager().isPrecipitatingAt(serverLevel, pos));
        }
    }

    @Inject(method = "isRaining", at = @At("HEAD"), cancellable = true)
    private void realtime_localised_weather$isRaining(CallbackInfoReturnable<Boolean> callbackInfo) {
        if ((Object) this instanceof ServerLevel serverLevel) {
            callbackInfo.setReturnValue(RealtimeLocalisedWeatherMod.serverWeatherManager().isGlobalRaining(serverLevel));
        }
    }

    @Inject(method = "isThundering", at = @At("HEAD"), cancellable = true)
    private void realtime_localised_weather$isThundering(CallbackInfoReturnable<Boolean> callbackInfo) {
        if ((Object) this instanceof ServerLevel serverLevel) {
            callbackInfo.setReturnValue(RealtimeLocalisedWeatherMod.serverWeatherManager().isGlobalThundering(serverLevel));
        }
    }
}
