package com.ukgeo.realtimelocalisedweather.network;

import com.ukgeo.realtimelocalisedweather.RealtimeLocalisedWeatherMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record WeatherTileUpdatePayload(String dimension, List<TileSnapshotPayload> tiles) implements CustomPacketPayload {
    public static final Type<WeatherTileUpdatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RealtimeLocalisedWeatherMod.MOD_ID, "weather_tile_update"));
    public static final net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, WeatherTileUpdatePayload> STREAM_CODEC = CustomPacketPayload.codec(WeatherTileUpdatePayload::write, WeatherTileUpdatePayload::read);

    private static void write(WeatherTileUpdatePayload payload, RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(payload.dimension, 128);
        buffer.writeVarInt(payload.tiles.size());
        for (TileSnapshotPayload tile : payload.tiles) {
            TileSnapshotPayload.write(tile, buffer);
        }
    }

    private static WeatherTileUpdatePayload read(RegistryFriendlyByteBuf buffer) {
        String dimension = buffer.readUtf(128);
        int size = buffer.readVarInt();
        List<TileSnapshotPayload> tiles = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            tiles.add(TileSnapshotPayload.read(buffer));
        }
        return new WeatherTileUpdatePayload(dimension, List.copyOf(tiles));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
