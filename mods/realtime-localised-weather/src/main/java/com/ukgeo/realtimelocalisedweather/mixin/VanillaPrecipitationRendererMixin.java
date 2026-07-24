package com.ukgeo.realtimelocalisedweather.mixin;

import com.ukgeo.realtimelocalisedweather.weather.client.ClientVisualWeatherController;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class VanillaPrecipitationRendererMixin {
    @Inject(method = "renderSnowAndRain", at = @At("HEAD"))
    private void realtime_localised_weather$renderSnowAndRain(LightTexture lightTexture, float partialTick, double cameraX, double cameraY, double cameraZ, CallbackInfo callbackInfo) {
        ClientVisualWeatherController.applyForCamera(cameraX, cameraZ);
    }
}
