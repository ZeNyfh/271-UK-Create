package git.zenyfh.vanilla_adjustments.mixin;

import git.zenyfh.vanilla_adjustments.PlayerPlacedWaterSourceHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Deque;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Shadow @Final
    Level level;

    @Unique
    private static final ThreadLocal<Deque<Boolean>> VANILLAADJUST$CHUNK_TARGET_WAS_WATER = ThreadLocal.withInitial(ArrayDeque::new);
    @Unique
    private static final ThreadLocal<Deque<Boolean>> VANILLAADJUST$CHUNK_TARGET_WAS_SOURCE_WATER = ThreadLocal.withInitial(ArrayDeque::new);

    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void vanillaadjust$captureExistingChunkWater(BlockPos pos, BlockState state, boolean isMoving, CallbackInfoReturnable<BlockState> callbackInfo) {
        boolean targetWasWater = this.level.getFluidState(pos).is(FluidTags.WATER);
        boolean targetWasSourceWater = targetWasWater && this.level.getFluidState(pos).isSource();
        VANILLAADJUST$CHUNK_TARGET_WAS_WATER.get().push(targetWasWater);
        VANILLAADJUST$CHUNK_TARGET_WAS_SOURCE_WATER.get().push(targetWasSourceWater);
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void vanillaadjust$trackChunkWaterPlacement(BlockPos pos, BlockState state, boolean isMoving, CallbackInfoReturnable<BlockState> callbackInfo) {
        Deque<Boolean> waterStack = VANILLAADJUST$CHUNK_TARGET_WAS_WATER.get();
        Deque<Boolean> sourceStack = VANILLAADJUST$CHUNK_TARGET_WAS_SOURCE_WATER.get();
        boolean targetWasWater = !waterStack.isEmpty() && waterStack.pop();
        boolean targetWasSourceWater = !sourceStack.isEmpty() && sourceStack.pop();
        if (waterStack.isEmpty()) {
            VANILLAADJUST$CHUNK_TARGET_WAS_WATER.remove();
        }
        if (sourceStack.isEmpty()) {
            VANILLAADJUST$CHUNK_TARGET_WAS_SOURCE_WATER.remove();
        }

        if (callbackInfo.getReturnValue() != null) {
            PlayerPlacedWaterSourceHandler.handleDirectWaterPlacement(
                    this.level,
                    pos,
                    state,
                    targetWasWater,
                    targetWasSourceWater,
                    "chunk water placement"
            );
        }
    }
}
