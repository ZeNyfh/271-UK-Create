package com.ukgeo.realtimelocalisedweather.network;

import com.ukgeo.realtimelocalisedweather.RealtimeLocalisedWeatherMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record WeatherLightningPayload(String dimension, int blockX, int blockY, int blockZ, boolean authoritative) implements CustomPacketPayload {
    public static final Type<WeatherLightningPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RealtimeLocalisedWeatherMod.MOD_ID, "weather_lightning"));
    public static final net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, WeatherLightningPayload> STREAM_CODEC = CustomPacketPayload.codec(WeatherLightningPayload::write, WeatherLightningPayload::read);

    private static void write(WeatherLightningPayload payload, RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(payload.dimension, 128);
        buffer.writeInt(payload.blockX);
        buffer.writeInt(payload.blockY);
        buffer.writeInt(payload.blockZ);
        buffer.writeBoolean(payload.authoritative);
    }

    private static WeatherLightningPayload read(RegistryFriendlyByteBuf buffer) {
        return new WeatherLightningPayload(buffer.readUtf(128), buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
