package git.zenyfh.vanilla_adjustments.mixin;

import git.zenyfh.vanilla_adjustments.PlayerPlacedWaterSourceHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Redirect(
            method = "tickPrecipitation",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"
            )
    )
    private boolean vanillaadjust$trackWaterOriginWhenFreezing(ServerLevel level, BlockPos pos, BlockState state) {
        boolean freezingWater = state.is(Blocks.ICE);
        boolean waterWasNonNatural = freezingWater && PlayerPlacedWaterSourceHandler.isNonNaturalWaterSource(level, pos);
        boolean changed = level.setBlockAndUpdate(pos, state);
        if (changed && freezingWater) {
            PlayerPlacedWaterSourceHandler.handleWaterFreeze(level, pos, waterWasNonNatural);
        }
        return changed;
    }
}
