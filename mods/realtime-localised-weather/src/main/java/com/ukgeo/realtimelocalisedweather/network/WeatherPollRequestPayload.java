package com.ukgeo.realtimelocalisedweather.network;

import com.ukgeo.realtimelocalisedweather.RealtimeLocalisedWeatherMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record WeatherPollRequestPayload(String dimension) implements CustomPacketPayload {
    public static final Type<WeatherPollRequestPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RealtimeLocalisedWeatherMod.MOD_ID, "weather_poll_request"));
    public static final net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, WeatherPollRequestPayload> STREAM_CODEC = CustomPacketPayload.codec(WeatherPollRequestPayload::write, WeatherPollRequestPayload::read);

    private static void write(WeatherPollRequestPayload payload, RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(payload.dimension, 128);
    }

    private static WeatherPollRequestPayload read(RegistryFriendlyByteBuf buffer) {
        return new WeatherPollRequestPayload(buffer.readUtf(128));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
