package com.ukgeo.realtimelocalisedweather.network;

import com.ukgeo.realtimelocalisedweather.RealtimeLocalisedWeatherMod;
import com.ukgeo.realtimelocalisedweather.weather.client.ClientWeatherManager;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid = RealtimeLocalisedWeatherMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class WeatherNetwork {
    private WeatherNetwork() {
    }

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(RealtimeLocalisedWeatherMod.PROTOCOL_VERSION);
        registrar.playToClient(WeatherProtocolPayload.TYPE, WeatherProtocolPayload.STREAM_CODEC, (payload, context) -> {
            if (!ProtocolVersions.isCompatible(payload.protocolVersion())) {
                context.disconnect(Component.literal("Realtime Localised Weather protocol mismatch. Client=" + RealtimeLocalisedWeatherMod.PROTOCOL_VERSION + " server=" + payload.protocolVersion()));
                return;
            }
            context.enqueueWork(() -> ClientWeatherManager.receiveProtocol(payload));
        });
        registrar.playToClient(UkGeoReferencePayload.TYPE, UkGeoReferencePayload.STREAM_CODEC, (payload, context) -> context.enqueueWork(() -> ClientWeatherManager.receiveReference(payload)));
        registrar.playToClient(WeatherInitialGridPayload.TYPE, WeatherInitialGridPayload.STREAM_CODEC, (payload, context) -> context.enqueueWork(() -> ClientWeatherManager.receiveInitialGrid(payload)));
        registrar.playToClient(WeatherTileUpdatePayload.TYPE, WeatherTileUpdatePayload.STREAM_CODEC, (payload, context) -> context.enqueueWork(() -> ClientWeatherManager.receiveUpdates(payload)));
        registrar.playToClient(WeatherTileRemovePayload.TYPE, WeatherTileRemovePayload.STREAM_CODEC, (payload, context) -> context.enqueueWork(() -> ClientWeatherManager.receiveRemovals(payload)));
        registrar.playToClient(WeatherAuthorityModePayload.TYPE, WeatherAuthorityModePayload.STREAM_CODEC, (payload, context) -> context.enqueueWork(() -> ClientWeatherManager.receiveMode(payload)));
        registrar.playToClient(WeatherLightningPayload.TYPE, WeatherLightningPayload.STREAM_CODEC, (payload, context) -> context.enqueueWork(() -> ClientWeatherManager.receiveLightning(payload)));
    }
}
