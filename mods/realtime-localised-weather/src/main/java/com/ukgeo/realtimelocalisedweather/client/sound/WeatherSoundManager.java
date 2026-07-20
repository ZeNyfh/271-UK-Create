package com.ukgeo.realtimelocalisedweather.client.sound;

import com.ukgeo.realtimelocalisedweather.config.ClientWeatherConfig;
import com.ukgeo.realtimelocalisedweather.weather.client.ClientWeatherManager;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

@EventBusSubscriber(modid = com.ukgeo.realtimelocalisedweather.RealtimeLocalisedWeatherMod.MOD_ID, value = Dist.CLIENT)
public final class WeatherSoundManager {
    private WeatherSoundManager() {
    }

    @SubscribeEvent
    public static void onClientTick(PlayerTickEvent.Post event) {
        if (!event.getEntity().level().isClientSide() || !ClientWeatherConfig.ENABLE_WEATHER_SOUNDS.get()) {
            return;
        }
        if (event.getEntity().tickCount % 80 != 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        var sample = ClientWeatherManager.sample(event.getEntity().blockPosition());
        if (sample.isEmpty() || !sample.get().snapshot().hasPrecipitation()) {
            return;
        }
        float volume = Math.min(1.0F, 0.15F + sample.get().interpolatedRate() / 8.0F);
        minecraft.level.playLocalSound(event.getEntity().getX(), event.getEntity().getY(), event.getEntity().getZ(), SoundEvents.WEATHER_RAIN, SoundSource.WEATHER, volume, 1.0F, false);
    }
}
