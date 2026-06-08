package git.zenyfh.vanilla_adjustments;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PollutionSyncPacket(float localPollution) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PollutionSyncPacket> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(Vanilla_adjustments.MODID, "pollution_sync")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, PollutionSyncPacket> STREAM_CODEC = CustomPacketPayload.codec(
            PollutionSyncPacket::write,
            PollutionSyncPacket::read
    );

    private static void write(PollutionSyncPacket packet, RegistryFriendlyByteBuf buffer) {
        buffer.writeFloat(packet.localPollution);
    }

    private static PollutionSyncPacket read(RegistryFriendlyByteBuf buffer) {
        return new PollutionSyncPacket(buffer.readFloat());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
