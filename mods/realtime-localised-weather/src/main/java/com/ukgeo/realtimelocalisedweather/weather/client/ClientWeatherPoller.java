package com.ukgeo.realtimelocalisedweather.weather.client;

import com.ukgeo.realtimelocalisedweather.RealtimeLocalisedWeatherMod;
import com.ukgeo.realtimelocalisedweather.config.ClientWeatherConfig;
import com.ukgeo.realtimelocalisedweather.network.WeatherPollRequestPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = RealtimeLocalisedWeatherMod.MOD_ID, value = Dist.CLIENT)
public final class ClientWeatherPoller {
    private static long nextPollGameTime;
    private static String lastPolledDimension = "";

    private ClientWeatherPoller() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ClientWeatherConfig.ENABLED.get()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.getConnection() == null) {
            nextPollGameTime = 0L;
            lastPolledDimension = "";
            return;
        }
        String dimension = minecraft.level.dimension().location().toString();
        long gameTime = minecraft.level.getGameTime();
        boolean dimensionChanged = !dimension.equals(lastPolledDimension);
        if (!dimensionChanged && gameTime < nextPollGameTime) {
            return;
        }
        lastPolledDimension = dimension;
        nextPollGameTime = gameTime + ClientWeatherConfig.weatherPollIntervalTicks();
        PacketDistributor.sendToServer(new WeatherPollRequestPayload(dimension));
    }
}
