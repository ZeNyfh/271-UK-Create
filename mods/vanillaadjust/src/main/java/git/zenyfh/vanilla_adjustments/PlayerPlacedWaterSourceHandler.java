package git.zenyfh.vanilla_adjustments;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public final class PlayerPlacedWaterSourceHandler {
    private static final ThreadLocal<NaturalSourcePlacement> NATURAL_SOURCE_PLACEMENT = new ThreadLocal<>();
    private static final int CONTRAPTION_WATER_MARK_RADIUS = 2;

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

        markNonNaturalWater(serverLevel, pos, reason);
    }

    public static void markNonNaturalWaterBlock(Level level, BlockPos pos, String reason) {
        if (!VanillaAdjustConfig.PLAYER_PLACED_WATER_SOURCE_LIMIT_ENABLED.get()
                || level.isClientSide()
                || !(level instanceof ServerLevel serverLevel)
                || !isWater(level.getFluidState(pos))) {
            return;
        }

        markNonNaturalWater(serverLevel, pos, reason);
    }

    private static void markNonNaturalWater(ServerLevel serverLevel, BlockPos pos, String reason) {
        PlayerPlacedWaterSourceSavedData data = PlayerPlacedWaterSourceSavedData.get(serverLevel);
        if (data.addNonNaturalWater(pos.asLong())) {
            debug("marked non-natural water at %s %s reason=%s", serverLevel.dimension().location(), pos, reason);
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
        if (!VanillaAdjustConfig.PLAYER_PLACED_WATER_SOURCE_LIMIT_ENABLED.get() || level.isClientSide()) {
            return false;
        }

        if (shouldSuppressContraptionWaterSourceConversion(level)) {
            if (level instanceof ServerLevel serverLevel) {
                // Some Create/Aeronautics/Sable contraption paths run fluid logic against a real
                // ServerLevel during snapping, assembly or disassembly. In those paths every involved
                // water block must be treated as non-natural, regardless of whether it was already a
                // source at the target position.
                markNearbyWaterAsContraptionWater(serverLevel, targetPos, "contraption fluid source conversion");
                debug(
                        "blocked source conversion from contraption stack at %s %s level=%s",
                        serverLevel.dimension().location(),
                        targetPos,
                        level.getClass().getName()
                );
            } else {
                // Create Aeronautics/Sable contraptions can tick fluids in server-side fake levels.
                // Those levels do not have our per-dimension saved-data markers, so the safest
                // behaviour is to prevent water from upgrading to a new source there at all.
                debug("blocked source conversion in non-ServerLevel %s at %s", level.getClass().getName(), targetPos);
            }
            return true;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }

        int naturalSources = 0;
        int nonNaturalSources = 0;
        if (isMarkedNonNaturalWater(serverLevel, targetPos)) {
            nonNaturalSources++;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbour = targetPos.relative(direction);
            if (!isSourceWater(level.getFluidState(neighbour))) {
                continue;
            }
            if (isMarkedNonNaturalWater(serverLevel, neighbour)) {
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


    /**
     * Gamerule-style suppression for Create/Aeronautics/Sable contraption fluid contexts.
     * This is deliberately separate from the real waterSourceConversion gamerule: it makes
     * contraption water behave as if source conversion is disabled without mutating global
     * world gamerules for the rest of the tick.
     */
    public static boolean shouldSuppressContraptionWaterSourceConversion(Level level) {
        if (!VanillaAdjustConfig.PLAYER_PLACED_WATER_SOURCE_LIMIT_ENABLED.get() || level.isClientSide()) {
            return false;
        }

        if (!(level instanceof ServerLevel)) {
            return true;
        }

        return isLikelyContraptionWaterPlacement();
    }

    /**
     * NeoForge CreateFluidSourceEvent fires for each neighbouring source block that is being
     * counted toward a new source. Blocking marked non-natural water here prevents the new
     * source from being created in the first place, which is much more reliable than trying to
     * repair the result after the fluid has already converted.
     */
    public static boolean shouldBlockCreateFluidSource(Level level, BlockPos sourcePos, BlockState sourceState) {
        if (!VanillaAdjustConfig.PLAYER_PLACED_WATER_SOURCE_LIMIT_ENABLED.get()
                || level.isClientSide()
                || !isWater(sourceState.getFluidState())) {
            return false;
        }

        if (shouldSuppressContraptionWaterSourceConversion(level)) {
            if (level instanceof ServerLevel serverLevel) {
                markNearbyWaterAsContraptionWater(serverLevel, sourcePos, "contraption CreateFluidSourceEvent");
                debug("blocked CreateFluidSourceEvent in contraption context at %s %s", serverLevel.dimension().location(), sourcePos);
            } else {
                debug("blocked CreateFluidSourceEvent in non-ServerLevel %s at %s", level.getClass().getName(), sourcePos);
            }
            return true;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }

        if (isMarkedNonNaturalWater(serverLevel, sourcePos) || hasNearbyMarkedNonNaturalWater(serverLevel, sourcePos)) {
            debug("blocked CreateFluidSourceEvent from non-natural water at %s %s", serverLevel.dimension().location(), sourcePos);
            return true;
        }

        return false;
    }

    public static void allowNextNaturalSourceWaterPlacement(Level level, BlockPos pos, String reason) {
        if (!VanillaAdjustConfig.PLAYER_PLACED_WATER_SOURCE_LIMIT_ENABLED.get()
                || level.isClientSide()
                || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        NATURAL_SOURCE_PLACEMENT.set(new NaturalSourcePlacement(serverLevel.dimension(), pos.asLong(), serverLevel.getGameTime()));
        debug("allowing next natural water source placement at %s %s reason=%s", serverLevel.dimension().location(), pos, reason);
    }

    public static void handleDirectWaterPlacement(Level level, BlockPos pos, BlockState state, boolean targetWasWater, String reason) {
        handleDirectWaterPlacement(level, pos, state, targetWasWater, targetWasWater && isSourceWater(level.getFluidState(pos)), reason);
    }

    public static void handleDirectWaterPlacement(Level level, BlockPos pos, BlockState state, boolean targetWasWater, boolean targetWasSourceWater, String reason) {
        if (!VanillaAdjustConfig.PLAYER_PLACED_WATER_SOURCE_LIMIT_ENABLED.get()
                || level.isClientSide()
                || !(level instanceof ServerLevel serverLevel)) {
            return;
        }

        FluidState placedFluid = state.getFluidState();
        if (!isWater(placedFluid)) {
            clearOrigin(level, pos);
            return;
        }

        if (placedFluid.isSource() && consumeAllowedNaturalSourceWaterPlacement(serverLevel, pos)) {
            PlayerPlacedWaterSourceSavedData.get(serverLevel).removeNonNaturalWater(pos.asLong());
            return;
        }

        if (isLikelyContraptionWaterPlacement()) {
            // Assembly/disassembly/movement may copy source or flowing water from a contraption
            // using many different call paths. Mark the whole local water cluster so flowing
            // pieces placed by the contraption cannot later upgrade into natural/infinite sources.
            markNearbyWaterAsContraptionWater(serverLevel, pos, reason + " from contraption");
            return;
        }

        if (placedFluid.isSource()) {
            boolean nearNonNaturalWater = hasNearbyMarkedNonNaturalWater(serverLevel, pos);
            if (!targetWasSourceWater || nearNonNaturalWater) {
                // This catches the important disassembly/snap case: a flowing/empty position is
                // replaced with a source block. It also re-marks source blocks placed beside an
                // already non-natural contraption water cluster, preventing partial naturalisation
                // when a moving contraption snaps/disassembles source water in multiple passes.
                markNonNaturalWater(serverLevel, pos, reason + " source placement");
            }
            return;
        }

        if (targetWasWater) {
            // Ordinary flowing-water updates that preserve existing water should not turn natural
            // lakes/rivers into artificial water. Contraption paths are handled above.
            return;
        }

        // Flowing water placed into an empty/non-water position is still artificial. Mark it so if
        // it later participates in source conversion, FlowingFluidMixin will block the upgrade.
        markNonNaturalWater(serverLevel, pos, reason + " flowing placement");
    }

    /**
     * Backwards-compatible entry point used by older source patches/classes.
     */
    public static void handleDirectSourceWaterPlacement(Level level, BlockPos pos, BlockState state, boolean targetWasSourceWater, String reason) {
        handleDirectWaterPlacement(level, pos, state, targetWasSourceWater, targetWasSourceWater, reason);
    }


    private static boolean hasNearbyMarkedNonNaturalWater(ServerLevel level, BlockPos center) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                cursor.set(center.getX() + dx, center.getY(), center.getZ() + dz);
                if (isMarkedNonNaturalWater(level, cursor)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void markNearbyWaterAsContraptionWater(ServerLevel level, BlockPos center, String reason) {
        int marked = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -CONTRAPTION_WATER_MARK_RADIUS; dx <= CONTRAPTION_WATER_MARK_RADIUS; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -CONTRAPTION_WATER_MARK_RADIUS; dz <= CONTRAPTION_WATER_MARK_RADIUS; dz++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (isWater(level.getFluidState(cursor))) {
                        markNonNaturalWater(level, cursor.immutable(), reason);
                        marked++;
                    }
                }
            }
        }
        if (marked > 0) {
            debug("marked %d nearby contraption water blocks around %s %s reason=%s", marked, level.dimension().location(), center, reason);
        }
    }

    private static boolean consumeAllowedNaturalSourceWaterPlacement(ServerLevel level, BlockPos pos) {
        NaturalSourcePlacement placement = NATURAL_SOURCE_PLACEMENT.get();
        if (placement == null) {
            return false;
        }
        if (placement.gameTime() != level.getGameTime()) {
            NATURAL_SOURCE_PLACEMENT.remove();
            return false;
        }
        if (placement.pos() == pos.asLong() && placement.dimension().equals(level.dimension())) {
            NATURAL_SOURCE_PLACEMENT.remove();
            return true;
        }
        return false;
    }

    private static boolean isLikelyContraptionWaterPlacement() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stack) {
            String className = element.getClassName().toLowerCase();
            if (className.contains("com.simibubi.create")
                    || className.contains("aeronautics")
                    || className.contains("simulated")
                    || className.contains("offroad")
                    || className.contains("sable")
                    || className.contains("contraption")
                    || className.contains("movement")
                    || className.contains("assembly")
                    || className.contains("disassembly")) {
                return true;
            }
        }
        return false;
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
            if (!isWater(level.getFluidState(pos)) && data.removeNonNaturalWater(key)) {
                debug("removed stale non-natural water marker at %s %s", level.dimension().location(), pos);
            }
            return false;
        }
        return true;
    }

    public static boolean isMarkedNonNaturalWater(ServerLevel level, BlockPos pos) {
        PlayerPlacedWaterSourceSavedData data = PlayerPlacedWaterSourceSavedData.get(level);
        long key = pos.asLong();
        if (!data.containsNonNaturalWater(key)) {
            return false;
        }
        if (!isWater(level.getFluidState(pos))) {
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

    private static boolean isWater(FluidState fluidState) {
        return fluidState.is(FluidTags.WATER);
    }

    private static boolean isSourceWater(FluidState fluidState) {
        return fluidState.is(FluidTags.WATER) && fluidState.isSource();
    }

    private static void debug(String message, Object... args) {
        if (VanillaAdjustConfig.DEBUG_PLAYER_PLACED_WATER_SOURCES.get()) {
            Vanilla_adjustments.LOGGER.info(message, args);
        }
    }

    private record NaturalSourcePlacement(ResourceKey<Level> dimension, long pos, long gameTime) {
    }
}
