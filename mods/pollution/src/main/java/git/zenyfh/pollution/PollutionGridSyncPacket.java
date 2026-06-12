package git.zenyfh.pollution;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PollutionGridSyncPacket(int centerChunkX, int centerChunkZ, int radius, boolean clear, float[] values, VisualSource[] sources) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PollutionGridSyncPacket> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(Pollution.MODID, "pollution_grid_sync")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, PollutionGridSyncPacket> STREAM_CODEC = CustomPacketPayload.codec(
            PollutionGridSyncPacket::write,
            PollutionGridSyncPacket::read
    );

    private static void write(PollutionGridSyncPacket packet, RegistryFriendlyByteBuf buffer) {
        buffer.writeInt(packet.centerChunkX);
        buffer.writeInt(packet.centerChunkZ);
        buffer.writeVarInt(packet.radius);
        buffer.writeBoolean(packet.clear);
        buffer.writeVarInt(packet.values.length);
        for (float value : packet.values) {
            buffer.writeFloat(value);
        }
        buffer.writeVarInt(packet.sources.length);
        for (VisualSource source : packet.sources) {
            buffer.writeInt(source.blockX);
            buffer.writeInt(source.blockY);
            buffer.writeInt(source.blockZ);
            buffer.writeFloat(source.emissionRate);
            buffer.writeFloat(source.localPollution);
            buffer.writeByte(source.sourceType);
        }
    }

    private static PollutionGridSyncPacket read(RegistryFriendlyByteBuf buffer) {
        int centerChunkX = buffer.readInt();
        int centerChunkZ = buffer.readInt();
        int radius = buffer.readVarInt();
        boolean clear = buffer.readBoolean();
        int length = buffer.readVarInt();
        float[] values = new float[length];
        for (int i = 0; i < length; i++) {
            values[i] = buffer.readFloat();
        }
        int sourceCount = buffer.readVarInt();
        VisualSource[] sources = new VisualSource[sourceCount];
        for (int i = 0; i < sourceCount; i++) {
            sources[i] = new VisualSource(
                    buffer.readInt(),
                    buffer.readInt(),
                    buffer.readInt(),
                    buffer.readFloat(),
                    buffer.readFloat(),
                    buffer.readByte()
            );
        }
        return new PollutionGridSyncPacket(centerChunkX, centerChunkZ, radius, clear, values, sources);
    }

    public static PollutionGridSyncPacket clearPacket() {
        return new PollutionGridSyncPacket(0, 0, 0, true, new float[0], new VisualSource[0]);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record VisualSource(int blockX, int blockY, int blockZ, float emissionRate, float localPollution, byte sourceType) {
    }
}
