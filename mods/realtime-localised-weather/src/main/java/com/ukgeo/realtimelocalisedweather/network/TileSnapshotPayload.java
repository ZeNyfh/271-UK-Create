package com.ukgeo.realtimelocalisedweather.network;

import com.ukgeo.realtimelocalisedweather.weather.ServerWeatherSnapshot;
import net.minecraft.network.RegistryFriendlyByteBuf;

public record TileSnapshotPayload(int tileX, int tileZ, long visualSeed, ServerWeatherSnapshot snapshot) {
    static void write(TileSnapshotPayload payload, RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(payload.tileX);
        buffer.writeVarInt(payload.tileZ);
        buffer.writeLong(payload.visualSeed);
        PayloadUtils.writeSnapshot(buffer, payload.snapshot);
    }

    static TileSnapshotPayload read(RegistryFriendlyByteBuf buffer) {
        return new TileSnapshotPayload(buffer.readVarInt(), buffer.readVarInt(), buffer.readLong(), PayloadUtils.readSnapshot(buffer));
    }
}
