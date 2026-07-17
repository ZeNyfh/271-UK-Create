package com.ukgeo.realtimelocalisedweather.network;

import com.ukgeo.realtimelocalisedweather.RealtimeLocalisedWeatherMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record WeatherTileRemovePayload(String dimension, List<Long> packedTileKeys) implements CustomPacketPayload {
    public static final Type<WeatherTileRemovePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RealtimeLocalisedWeatherMod.MOD_ID, "weather_tile_remove"));
    public static final net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, WeatherTileRemovePayload> STREAM_CODEC = CustomPacketPayload.codec(WeatherTileRemovePayload::write, WeatherTileRemovePayload::read);

    private static void write(WeatherTileRemovePayload payload, RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(payload.dimension, 128);
        buffer.writeVarInt(payload.packedTileKeys.size());
        for (long packedTileKey : payload.packedTileKeys) {
            buffer.writeLong(packedTileKey);
        }
    }

    private static WeatherTileRemovePayload read(RegistryFriendlyByteBuf buffer) {
        String dimension = buffer.readUtf(128);
        int size = buffer.readVarInt();
        List<Long> keys = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            keys.add(buffer.readLong());
        }
        return new WeatherTileRemovePayload(dimension, List.copyOf(keys));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
