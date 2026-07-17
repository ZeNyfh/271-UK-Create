package com.ukgeo.realtimelocalisedweather.mixin;

import com.ukgeo.realtimelocalisedweather.RealtimeLocalisedWeatherMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelWeatherMixin {
    @Inject(method = "tickPrecipitation(Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"), cancellable = true)
    private void realtime_localised_weather$tickPrecipitation(BlockPos blockPos, CallbackInfo callbackInfo) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (RealtimeLocalisedWeatherMod.serverWeatherManager().usesRegionalWeather(level)) {
            callbackInfo.cancel();
        }
    }
}
