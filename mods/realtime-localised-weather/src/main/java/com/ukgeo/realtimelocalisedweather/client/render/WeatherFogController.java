package com.ukgeo.realtimelocalisedweather.client.render;

import com.ukgeo.realtimelocalisedweather.config.ClientWeatherConfig;
import com.ukgeo.realtimelocalisedweather.weather.client.ClientWeatherManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;

@EventBusSubscriber(modid = com.ukgeo.realtimelocalisedweather.RealtimeLocalisedWeatherMod.MOD_ID, value = Dist.CLIENT)
public final class WeatherFogController {
    private WeatherFogController() {
    }

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (!ClientWeatherConfig.ENABLE_WEATHER_FOG.get()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        var sample = ClientWeatherManager.sample(minecraft.player.blockPosition());
        if (sample.isEmpty()) {
            return;
        }
        float rain = Math.min(1.0F, sample.get().interpolatedRate() / 8.0F);
        float multiplier = 1.0F - (rain * 0.35F);
        event.scaleFarPlaneDistance(Math.max(0.45F, multiplier));
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onFogColour(ViewportEvent.ComputeFogColor event) {
        if (!ClientWeatherConfig.ENABLE_WEATHER_FOG.get()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        var sample = ClientWeatherManager.sample(new BlockPos(minecraft.player.getBlockX(), minecraft.player.getBlockY(), minecraft.player.getBlockZ()));
        if (sample.isEmpty()) {
            return;
        }
        float rain = Math.min(1.0F, sample.get().interpolatedRate() / 10.0F);
        event.setRed(event.getRed() * (1.0F - rain * 0.25F));
        event.setGreen(event.getGreen() * (1.0F - rain * 0.2F));
        event.setBlue(event.getBlue() * (1.0F - rain * 0.1F));
    }
}
