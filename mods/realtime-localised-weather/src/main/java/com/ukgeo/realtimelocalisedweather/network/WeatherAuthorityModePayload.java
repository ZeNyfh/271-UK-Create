package com.ukgeo.realtimelocalisedweather.network;

import com.ukgeo.realtimelocalisedweather.RealtimeLocalisedWeatherMod;
import com.ukgeo.realtimelocalisedweather.weather.WeatherAuthorityMode;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record WeatherAuthorityModePayload(String dimension, WeatherAuthorityMode mode) implements CustomPacketPayload {
    public static final Type<WeatherAuthorityModePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RealtimeLocalisedWeatherMod.MOD_ID, "weather_authority_mode"));
    public static final net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, WeatherAuthorityModePayload> STREAM_CODEC = CustomPacketPayload.codec(WeatherAuthorityModePayload::write, WeatherAuthorityModePayload::read);

    private static void write(WeatherAuthorityModePayload payload, RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(payload.dimension, 128);
        PayloadUtils.writeEnum(buffer, payload.mode);
    }

    private static WeatherAuthorityModePayload read(RegistryFriendlyByteBuf buffer) {
        return new WeatherAuthorityModePayload(buffer.readUtf(128), PayloadUtils.readAuthorityMode(buffer));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
