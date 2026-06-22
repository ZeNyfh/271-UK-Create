package git.zenyfh.vanilla_adjustments;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public final class PlayerPlacedWaterSourceHandler {
    private PlayerPlacedWaterSourceHandler() {
    }

    public static void markPlayerPlacedWaterSource(Player player, Level level, BlockPos pos) {
        if (player == null) {
            return;
        }
        markNonNaturalWaterSource(level, pos, "player bucket");
    }

    public static void markNonNaturalWaterSource(Level level, BlockPos pos, String reason) {
        if (!VanillaAdjustConfig.PLAYER_PLACED_WATER_SOURCE_LIMIT_ENABLED.get()
                || level.isClientSide()
                || !(level instanceof ServerLevel serverLevel)
                || !isSourceWater(level.getFluidState(pos))) {
            return;
        }

        PlayerPlacedWaterSourceSavedData data = PlayerPlacedWaterSourceSavedData.get(serverLevel);
        if (data.addNonNaturalWater(pos.asLong())) {
            debug("marked non-natural water source at %s %s reason=%s", serverLevel.dimension().location(), pos, reason);
        }
        data.removeNonNaturalIce(pos.asLong());
    }

    public static void markNonNaturalIce(Level level, BlockPos pos, String reason) {
        if (!VanillaAdjustConfig.PLAYER_PLACED_WATER_SOURCE_LIMIT_ENABLED.get()
                || level.isClientSide()
                || !(level instanceof ServerLevel serverLevel)
                || !isTrackableIce(level.getBlockState(pos))) {
            return;
        }

        PlayerPlacedWaterSourceSavedData data = PlayerPlacedWaterSourceSavedData.get(serverLevel);
        if (data.addNonNaturalIce(pos.asLong())) {
            debug("marked non-natural ice at %s %s reason=%s", serverLevel.dimension().location(), pos, reason);
        }
        data.removeNonNaturalWater(pos.asLong());
    }

    public static void handleWaterFreeze(ServerLevel level, BlockPos pos, boolean waterWasNonNatural) {
        if (!VanillaAdjustConfig.PLAYER_PLACED_WATER_SOURCE_LIMIT_ENABLED.get()) {
            return;
        }
        PlayerPlacedWaterSourceSavedData data = PlayerPlacedWaterSourceSavedData.get(level);
        data.removeNonNaturalWater(pos.asLong());
        if (waterWasNonNatural && isTrackableIce(level.getBlockState(pos))) {
            if (data.addNonNaturalIce(pos.asLong())) {
                debug("propagated non-natural water to frozen ice at %s %s", level.dimension().location(), pos);
            }
        } else {
            data.removeNonNaturalIce(pos.asLong());
        }
    }

    public static void handleIceThaw(Level level, BlockPos pos, boolean iceWasNonNatural) {
        if (!VanillaAdjustConfig.PLAYER_PLACED_WATER_SOURCE_LIMIT_ENABLED.get()
                || level.isClientSide()
                || !(level instanceof ServerLevel serverLevel)) {
            return;
        }

        PlayerPlacedWaterSourceSavedData data = PlayerPlacedWaterSourceSavedData.get(serverLevel);
        data.removeNonNaturalIce(pos.asLong());
        if (iceWasNonNatural && isSourceWater(level.getFluidState(pos))) {
            if (data.addNonNaturalWater(pos.asLong())) {
                debug("propagated non-natural ice to thawed water at %s %s", serverLevel.dimension().location(), pos);
            }
        } else if (!iceWasNonNatural && isSourceWater(level.getFluidState(pos))) {
            data.removeNonNaturalWater(pos.asLong());
        }
    }

    public static void clearOrigin(Level level, BlockPos pos) {
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            PlayerPlacedWaterSourceSavedData.get(serverLevel).removeAll(pos.asLong());
        }
    }

    public static boolean shouldBlockWaterSourceConversion(Level level, BlockPos targetPos) {
        if (!VanillaAdjustConfig.PLAYER_PLACED_WATER_SOURCE_LIMIT_ENABLED.get()
                || level.isClientSide()
                || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }

        int naturalSources = 0;
        int nonNaturalSources = 0;
        if (isNonNaturalWaterSource(serverLevel, targetPos)) {
            nonNaturalSources++;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbour = targetPos.relative(direction);
            if (!isSourceWater(level.getFluidState(neighbour))) {
                continue;
            }
            if (isNonNaturalWaterSource(serverLevel, neighbour)) {
                nonNaturalSources++;
            } else {
                naturalSources++;
            }
        }

        boolean blocked = nonNaturalSources > 0;
        if (blocked) {
            debug(
                    "blocked source conversion at %s %s natural=%d nonNatural=%d",
                    serverLevel.dimension().location(),
                    targetPos,
                    naturalSources,
                    nonNaturalSources
            );
        }
        return blocked;
    }

    public static boolean isPlayerPlacedWaterSource(ServerLevel level, BlockPos pos) {
        return isNonNaturalWaterSource(level, pos);
    }

    public static boolean isNonNaturalWaterSource(ServerLevel level, BlockPos pos) {
        PlayerPlacedWaterSourceSavedData data = PlayerPlacedWaterSourceSavedData.get(level);
        long key = pos.asLong();
        if (!data.containsNonNaturalWater(key)) {
            return false;
        }
        if (!isSourceWater(level.getFluidState(pos))) {
            if (data.removeNonNaturalWater(key)) {
                debug("removed stale non-natural water marker at %s %s", level.dimension().location(), pos);
            }
            return false;
        }
        return true;
    }

    public static boolean isNonNaturalIce(ServerLevel level, BlockPos pos) {
        PlayerPlacedWaterSourceSavedData data = PlayerPlacedWaterSourceSavedData.get(level);
        long key = pos.asLong();
        if (!data.containsNonNaturalIce(key)) {
            return false;
        }
        if (!isTrackableIce(level.getBlockState(pos))) {
            if (data.removeNonNaturalIce(key)) {
                debug("removed stale non-natural ice marker at %s %s", level.dimension().location(), pos);
            }
            return false;
        }
        return true;
    }

    public static boolean isTrackableIce(BlockState state) {
        return state.is(Blocks.ICE)
                || state.is(Blocks.FROSTED_ICE)
                || state.is(Blocks.PACKED_ICE)
                || state.is(Blocks.BLUE_ICE);
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
