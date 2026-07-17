package com.ukgeo.realtimelocalisedweather.network;

import com.ukgeo.realtimelocalisedweather.RealtimeLocalisedWeatherMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record WeatherProtocolPayload(String protocolVersion, String modVersion) implements CustomPacketPayload {
    public static final Type<WeatherProtocolPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RealtimeLocalisedWeatherMod.MOD_ID, "weather_protocol"));
    public static final net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, WeatherProtocolPayload> STREAM_CODEC = CustomPacketPayload.codec(WeatherProtocolPayload::write, WeatherProtocolPayload::read);

    private static void write(WeatherProtocolPayload payload, RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(payload.protocolVersion, 32);
        buffer.writeUtf(payload.modVersion, 32);
    }

    private static WeatherProtocolPayload read(RegistryFriendlyByteBuf buffer) {
        return new WeatherProtocolPayload(buffer.readUtf(32), buffer.readUtf(32));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
