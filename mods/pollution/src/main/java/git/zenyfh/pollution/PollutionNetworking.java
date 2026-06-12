package git.zenyfh.pollution;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid = Pollution.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class PollutionNetworking {
    private PollutionNetworking() {
    }

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .optional()
                .playToClient(PollutionGridSyncPacket.TYPE, PollutionGridSyncPacket.STREAM_CODEC, (packet, context) ->
                        context.enqueueWork(() -> PollutionClientState.receive(packet)));
    }
}
