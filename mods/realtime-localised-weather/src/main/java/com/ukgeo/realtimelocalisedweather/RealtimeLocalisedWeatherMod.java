package com.ukgeo.realtimelocalisedweather;

import com.ukgeo.realtimelocalisedweather.config.ClientWeatherConfig;
import com.ukgeo.realtimelocalisedweather.config.ServerWeatherConfig;
import com.ukgeo.realtimelocalisedweather.weather.server.ServerWeatherManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(RealtimeLocalisedWeatherMod.MOD_ID)
public final class RealtimeLocalisedWeatherMod {
    public static final String MOD_ID = "realtime_localised_weather";
    public static final String MOD_VERSION = "1.0.0";
    public static final String PROTOCOL_VERSION = "rlw-1";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final ServerWeatherManager SERVER_WEATHER_MANAGER = new ServerWeatherManager();

    public RealtimeLocalisedWeatherMod(IEventBus modBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, ServerWeatherConfig.SPEC);
        modContainer.registerConfig(ModConfig.Type.CLIENT, ClientWeatherConfig.SPEC);
        NeoForge.EVENT_BUS.register(SERVER_WEATHER_MANAGER);
        NeoForge.EVENT_BUS.addListener(RealtimeWeatherCommands::register);
    }

    public static ServerWeatherManager serverWeatherManager() {
        return SERVER_WEATHER_MANAGER;
    }
}
