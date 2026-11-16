package com.ukgeo.realtimelocalisedweather.mixin;

import com.ukgeo.realtimelocalisedweather.config.ClientWeatherConfig;
import com.ukgeo.realtimelocalisedweather.weather.client.ClientWeatherManager;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla {@code tickRain} spawns RAIN splash particles based on biome precipitation type.
 * UK temperate biomes report RAIN even when this mod visually renders snow, so cancel
 * splash/drip spawning while our sample is snowy (and honour enable_splashes).
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererRainMixin {
    @Inject(method = "tickRain", at = @At("HEAD"), cancellable = true)
    private void realtime_localised_weather$tickRain(Camera camera, CallbackInfo cir) {
        if (!ClientWeatherManager.shouldReplaceVanillaWeather()) {
            return;
        }
        var sample = ClientWeatherManager.sample(camera.getBlockPosition());
        if (sample.isPresent() && sample.get().snapshot().resolvedPrecipitation().isSnowy()) {
            cir.cancel();
            return;
        }
        if (!ClientWeatherConfig.ENABLE_SPLASHES.get()) {
            cir.cancel();
        }
    }
}
