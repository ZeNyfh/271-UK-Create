package git.zenyfh.vanilla_adjustments;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

public final class DisableDripstoneLavaHandler {
    private DisableDripstoneLavaHandler() {
    }

    public static boolean shouldBlockDripstoneCauldronFill(ServerLevel level, BlockPos stalactiteTipPos, Fluid fluid) {
        boolean block = fluid == Fluids.LAVA && VanillaAdjustConfig.DISABLE_DRIPSTONE_LAVA_GENERATION.get()
                || fluid == Fluids.WATER && VanillaAdjustConfig.DISABLE_DRIPSTONE_WATER_GENERATION.get();
        if (block && VanillaAdjustConfig.DEBUG_DRIPSTONE_CAULDRON_BLOCKING.get()) {
            Vanilla_adjustments.LOGGER.info(
                    "blocked dripstone cauldron fill at {} {} fluid={}",
                    level.dimension().location(),
                    stalactiteTipPos,
                    fluid
            );
        }
        return block;
    }
}
