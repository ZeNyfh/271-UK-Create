package com.ukgeo.realtimelocalisedweather.weather.client;

import com.ukgeo.realtimelocalisedweather.RealtimeLocalisedWeatherMod;
import com.ukgeo.realtimelocalisedweather.config.ClientWeatherConfig;
import com.ukgeo.realtimelocalisedweather.weather.WeatherMath;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Drives only the local client's vanilla rain/thunder gradients.  Vanilla LevelRenderer then
 * remains responsible for ordinary rain and snow meshes, particles, audio, lighting, and roofs.
 */
@EventBusSubscriber(modid = RealtimeLocalisedWeatherMod.MOD_ID, value = Dist.CLIENT)
public final class ClientVisualWeatherController {
    private static float rainLevel;
    private static float thunderLevel;
    private static boolean initialized;

    private ClientVisualWeatherController() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ClientWeatherConfig.ENABLED.get() || minecraft.level == null || minecraft.player == null
            || !ClientWeatherManager.hasRealtimeData()) {
            initialized = false;
            return;
        }
        applyForCamera(minecraft.gameRenderer.getMainCamera().getPosition().x, minecraft.gameRenderer.getMainCamera().getPosition().z);
    }

    /**
     * Applies the sampled weather immediately before a vanilla render pass.  Server weather
     * packets are also processed on the client thread, so doing this only during ticks can let a
     * packet reset the gradient between sampling and LevelRenderer drawing precipitation.
     */
    public static void applyForCamera(double cameraX, double cameraZ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ClientWeatherConfig.ENABLED.get() || minecraft.level == null || minecraft.player == null
            || !ClientWeatherManager.hasRealtimeData()) {
            initialized = false;
            return;
        }
        BlockPos cameraPosition = BlockPos.containing(cameraX, minecraft.gameRenderer.getMainCamera().getPosition().y, cameraZ);
        var sample = ClientWeatherManager.sample(cameraPosition);
        if (sample.isEmpty()) return;

        var weather = sample.get();
        float targetRain = VisualWeatherMath.precipitationRateToRainLevel(
            weather.interpolatedRate(), ClientWeatherConfig.PRECIPITATION_DENSITY_MULTIPLIER.get().floatValue()
        );
        float targetThunder = WeatherMath.clamp01(weather.snapshot().thunderPotential()) * targetRain;
        float transitionTicks = Math.max(1.0F, (float) ClientWeatherConfig.transitionSeconds() * 20.0F);
        float blend = 1.0F - (float) Math.exp(-1.0F / transitionTicks);
        if (ClientWeatherManager.isVisualTestSample(weather)) {
            // Debug values are literal visual checks; do not conceal a regional-rain test
            // behind the normal live-weather easing interval.
            rainLevel = targetRain;
            thunderLevel = targetThunder;
            initialized = true;
        } else if (!initialized) {
            rainLevel = targetRain;
            thunderLevel = targetThunder;
            initialized = true;
        } else {
            rainLevel = WeatherMath.lerp(rainLevel, targetRain, blend);
            thunderLevel = WeatherMath.lerp(thunderLevel, targetThunder, blend);
        }
        minecraft.level.setRainLevel(WeatherMath.clamp01(rainLevel));
        minecraft.level.setThunderLevel(WeatherMath.clamp01(thunderLevel));
    }
}
