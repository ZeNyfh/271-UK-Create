package git.zenyfh.vanilla_adjustments;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.block.CreateFluidSourceEvent;

/**
 * Blocks water source creation in contraption fluid contexts and from tracked
 * non-natural water. This mirrors the useful part of the waterSourceConversion
 * gamerule without mutating the actual global gamerule.
 */
public final class ContraptionWaterSourceConversionHandler {
    @SubscribeEvent
    public void onCreateFluidSource(CreateFluidSourceEvent event) {
        if (PlayerPlacedWaterSourceHandler.shouldBlockCreateFluidSource(
                event.getLevel(),
                event.getPos(),
                event.getState()
        )) {
            event.setCanConvert(false);
        }
    }
}
