package git.zenyfh.vanilla_adjustments;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;

public final class PlayerPlacedWaterSourceHandler {
    private PlayerPlacedWaterSourceHandler() {
    }

    public static void markPlayerPlacedWaterSource(Player player, Level level, BlockPos pos) {
        if (!VanillaAdjustConfig.PLAYER_PLACED_WATER_SOURCE_LIMIT_ENABLED.get()
                || level.isClientSide()
                || !(level instanceof ServerLevel serverLevel)
                || player == null
                || !isSourceWater(level.getFluidState(pos))) {
            return;
        }

        PlayerPlacedWaterSourceSavedData.get(serverLevel).add(pos.asLong());
        debug("marked artificial water source at %s %s", serverLevel.dimension().location(), pos);
    }

    public static boolean shouldBlockWaterSourceConversion(Level level, BlockPos targetPos) {
        if (!VanillaAdjustConfig.PLAYER_PLACED_WATER_SOURCE_LIMIT_ENABLED.get()
                || level.isClientSide()
                || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }

        int naturalSources = 0;
        int artificialSources = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbour = targetPos.relative(direction);
            if (!isSourceWater(level.getFluidState(neighbour))) {
                continue;
            }
            if (isPlayerPlacedWaterSource(serverLevel, neighbour)) {
                artificialSources++;
            } else {
                naturalSources++;
            }
        }

        boolean blocked = naturalSources < 2 && artificialSources > 0;
        if (blocked) {
            debug(
                    "blocked source conversion at %s %s natural=%d artificial=%d",
                    serverLevel.dimension().location(),
                    targetPos,
                    naturalSources,
                    artificialSources
            );
        }
        return blocked;
    }

    public static boolean isPlayerPlacedWaterSource(ServerLevel level, BlockPos pos) {
        PlayerPlacedWaterSourceSavedData data = PlayerPlacedWaterSourceSavedData.get(level);
        long key = pos.asLong();
        if (!data.contains(key)) {
            return false;
        }
        if (!isSourceWater(level.getFluidState(pos))) {
            data.remove(key);
            debug("removed stale artificial water source at %s %s", level.dimension().location(), pos);
            return false;
        }
        return true;
    }

    private static boolean isSourceWater(FluidState fluidState) {
        return fluidState.is(FluidTags.WATER) && fluidState.isSource();
    }

    private static void debug(String message, Object... args) {
        if (VanillaAdjustConfig.DEBUG_PLAYER_PLACED_WATER_SOURCES.get()) {
            Vanilla_adjustments.LOGGER.info(message, args);
        }
    }
}
