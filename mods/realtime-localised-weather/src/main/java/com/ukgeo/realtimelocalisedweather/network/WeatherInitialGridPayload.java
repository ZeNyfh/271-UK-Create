package com.ukgeo.realtimelocalisedweather.network;

import com.ukgeo.realtimelocalisedweather.RealtimeLocalisedWeatherMod;
import com.ukgeo.realtimelocalisedweather.weather.WeatherAuthorityMode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record WeatherInitialGridPayload(String dimension, WeatherAuthorityMode mode, String season, String subSeason, List<TileSnapshotPayload> tiles) implements CustomPacketPayload {
    public static final Type<WeatherInitialGridPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RealtimeLocalisedWeatherMod.MOD_ID, "weather_initial_grid"));
    public static final net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, WeatherInitialGridPayload> STREAM_CODEC = CustomPacketPayload.codec(WeatherInitialGridPayload::write, WeatherInitialGridPayload::read);

    private static void write(WeatherInitialGridPayload payload, RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(payload.dimension, 128);
        PayloadUtils.writeEnum(buffer, payload.mode);
        buffer.writeUtf(payload.season, 64);
        buffer.writeUtf(payload.subSeason, 64);
        buffer.writeVarInt(payload.tiles.size());
        for (TileSnapshotPayload tile : payload.tiles) {
            TileSnapshotPayload.write(tile, buffer);
        }
    }

    private static WeatherInitialGridPayload read(RegistryFriendlyByteBuf buffer) {
        String dimension = buffer.readUtf(128);
        WeatherAuthorityMode mode = PayloadUtils.readAuthorityMode(buffer);
        String season = buffer.readUtf(64);
        String subSeason = buffer.readUtf(64);
        int size = buffer.readVarInt();
        List<TileSnapshotPayload> tiles = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            tiles.add(TileSnapshotPayload.read(buffer));
        }
        return new WeatherInitialGridPayload(dimension, mode, season, subSeason, List.copyOf(tiles));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
