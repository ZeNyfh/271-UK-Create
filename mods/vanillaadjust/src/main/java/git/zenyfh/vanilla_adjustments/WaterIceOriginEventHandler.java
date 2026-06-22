package git.zenyfh.vanilla_adjustments;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

public final class WaterIceOriginEventHandler {
    @SubscribeEvent
    public void onEntityPlaceBlock(BlockEvent.EntityPlaceEvent event) {
        if (!VanillaAdjustConfig.PLAYER_PLACED_WATER_SOURCE_LIMIT_ENABLED.get()
                || !(event.getLevel() instanceof ServerLevel level)
                || !PlayerPlacedWaterSourceHandler.isTrackableIce(event.getPlacedBlock())) {
            return;
        }

        PlayerPlacedWaterSourceHandler.markNonNaturalIce(level, event.getPos(), "entity block placement");
    }
}
