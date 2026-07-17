package com.ukgeo.realtimelocalisedweather.network;

import com.ukgeo.realtimelocalisedweather.RealtimeLocalisedWeatherMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record UkGeoReferencePayload(
    String dimension,
    String crs,
    int minecraftMinX,
    int minecraftMinZ,
    int minecraftMaxX,
    int minecraftMaxZ,
    double bngMinEasting,
    double bngMinNorthing,
    double bngMaxEasting,
    double bngMaxNorthing,
    int rasterWidth,
    int rasterDepth,
    int zoneSizeBlocks
) implements CustomPacketPayload {
    public static final Type<UkGeoReferencePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RealtimeLocalisedWeatherMod.MOD_ID, "ukgeo_reference"));
    public static final net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, UkGeoReferencePayload> STREAM_CODEC = CustomPacketPayload.codec(UkGeoReferencePayload::write, UkGeoReferencePayload::read);

    private static void write(UkGeoReferencePayload payload, RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(payload.dimension, 128);
        buffer.writeUtf(payload.crs, 32);
        buffer.writeInt(payload.minecraftMinX);
        buffer.writeInt(payload.minecraftMinZ);
        buffer.writeInt(payload.minecraftMaxX);
        buffer.writeInt(payload.minecraftMaxZ);
        buffer.writeDouble(payload.bngMinEasting);
        buffer.writeDouble(payload.bngMinNorthing);
        buffer.writeDouble(payload.bngMaxEasting);
        buffer.writeDouble(payload.bngMaxNorthing);
        buffer.writeVarInt(payload.rasterWidth);
        buffer.writeVarInt(payload.rasterDepth);
        buffer.writeVarInt(payload.zoneSizeBlocks);
    }

    private static UkGeoReferencePayload read(RegistryFriendlyByteBuf buffer) {
        return new UkGeoReferencePayload(
            buffer.readUtf(128),
            buffer.readUtf(32),
            buffer.readInt(),
            buffer.readInt(),
            buffer.readInt(),
            buffer.readInt(),
            buffer.readDouble(),
            buffer.readDouble(),
            buffer.readDouble(),
            buffer.readDouble(),
            buffer.readVarInt(),
            buffer.readVarInt(),
            buffer.readVarInt()
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
