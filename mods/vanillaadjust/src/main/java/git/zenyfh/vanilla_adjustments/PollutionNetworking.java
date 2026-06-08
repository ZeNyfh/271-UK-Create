package git.zenyfh.vanilla_adjustments;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid = Vanilla_adjustments.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class PollutionNetworking {
    private PollutionNetworking() {
    }

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(PollutionSyncPacket.TYPE, PollutionSyncPacket.STREAM_CODEC, (packet, context) ->
                        context.enqueueWork(() -> PollutionClientState.receive(packet.localPollution())));
    }
}
