package git.zenyfh.vanilla_adjustments;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record DeathTimerSyncPacket(boolean active, int remainingTicks, int totalTicks) implements CustomPacketPayload {
    public static final Type<DeathTimerSyncPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Vanilla_adjustments.MODID, "death_timer_sync")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, DeathTimerSyncPacket> STREAM_CODEC = CustomPacketPayload.codec(
            DeathTimerSyncPacket::write,
            DeathTimerSyncPacket::read
    );

    public static DeathTimerSyncPacket clear() {
        return new DeathTimerSyncPacket(false, 0, 0);
    }

    private static void write(DeathTimerSyncPacket packet, RegistryFriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.active);
        buffer.writeVarInt(packet.remainingTicks);
        buffer.writeVarInt(packet.totalTicks);
    }

    private static DeathTimerSyncPacket read(RegistryFriendlyByteBuf buffer) {
        return new DeathTimerSyncPacket(buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
