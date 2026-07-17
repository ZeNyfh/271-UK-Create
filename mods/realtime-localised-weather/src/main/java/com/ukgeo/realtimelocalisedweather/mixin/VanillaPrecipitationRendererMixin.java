package com.ukgeo.realtimelocalisedweather.mixin;

import com.ukgeo.realtimelocalisedweather.client.render.LocalisedPrecipitationRenderer;
import com.ukgeo.realtimelocalisedweather.weather.client.ClientWeatherManager;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class VanillaPrecipitationRendererMixin {
    @Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true)
    private void realtime_localised_weather$renderSnowAndRain(LightTexture lightTexture, float partialTick, double cameraX, double cameraY, double cameraZ, CallbackInfo callbackInfo) {
        if (ClientWeatherManager.shouldReplaceVanillaWeather()) {
            LocalisedPrecipitationRenderer.render(partialTick, cameraX, cameraY, cameraZ);
            callbackInfo.cancel();
        }
    }
}
