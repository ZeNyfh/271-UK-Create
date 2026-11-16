package com.ukgeo.realtimelocalisedweather.client.sound;

import com.ukgeo.realtimelocalisedweather.config.ClientWeatherConfig;
import com.ukgeo.realtimelocalisedweather.weather.client.ClientWeatherManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Continuous rain ambient for CUSTOM precip rendering (vanilla mesh cancelled).
 * One looping instance — never re-playLocalSound every N ticks (that cut every ~1–4s).
 */
@EventBusSubscriber(modid = com.ukgeo.realtimelocalisedweather.RealtimeLocalisedWeatherMod.MOD_ID, value = Dist.CLIENT)
public final class WeatherSoundManager {
    private static SoundInstance loopingRain;

    private WeatherSoundManager() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ClientWeatherConfig.ENABLE_WEATHER_SOUNDS.get()
            || minecraft.level == null
            || minecraft.player == null
            || !ClientWeatherManager.shouldReplaceVanillaWeather()) {
            stop(minecraft);
            return;
        }
        var sample = ClientWeatherManager.sample(minecraft.player.blockPosition());
        // Liquid precip only — snow must not loop WEATHER_RAIN ambient.
        boolean raining = sample.isPresent()
            && sample.get().snapshot().hasPrecipitation()
            && !sample.get().snapshot().resolvedPrecipitation().isSnowy()
            && sample.get().interpolatedRate() > 0.05F;
        if (!raining) {
            stop(minecraft);
            return;
        }
        if (loopingRain != null && minecraft.getSoundManager().isActive(loopingRain)) {
            return;
        }
        float volume = Math.min(1.0F, 0.20F + sample.get().interpolatedRate() / 8.0F);
        loopingRain = SimpleSoundInstance.forLocalAmbience(SoundEvents.WEATHER_RAIN, 1.0F, volume);
        // forLocalAmbience may not loop — fall back to explicit looping constructor if needed.
        if (loopingRain != null) {
            minecraft.getSoundManager().play(loopingRain);
        }
    }

    private static void stop(Minecraft minecraft) {
        if (loopingRain != null && minecraft.getSoundManager() != null) {
            minecraft.getSoundManager().stop(loopingRain);
        }
        loopingRain = null;
    }
}
