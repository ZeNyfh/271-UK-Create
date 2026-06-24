package git.zenyfh.vanilla_adjustments.mixin;

import git.zenyfh.vanilla_adjustments.PlayerPlacedWaterSourceHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Deque;

@Mixin(Level.class)
public abstract class LevelMixin {
    @Unique
    private static final ThreadLocal<Deque<Boolean>> VANILLAADJUST$TARGET_WAS_WATER = ThreadLocal.withInitial(ArrayDeque::new);
    @Unique
    private static final ThreadLocal<Deque<Boolean>> VANILLAADJUST$TARGET_WAS_SOURCE_WATER = ThreadLocal.withInitial(ArrayDeque::new);

    @Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD")
    )
    private void vanillaadjust$captureExistingWater(BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> callbackInfo) {
        Level level = (Level) (Object) this;
        boolean targetWasWater = level.getFluidState(pos).is(FluidTags.WATER);
        boolean targetWasSourceWater = level.getFluidState(pos).is(FluidTags.WATER) && level.getFluidState(pos).isSource();
        VANILLAADJUST$TARGET_WAS_WATER.get().push(targetWasWater);
        VANILLAADJUST$TARGET_WAS_SOURCE_WATER.get().push(targetWasSourceWater);
    }

    @Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("RETURN")
    )
    private void vanillaadjust$trackDirectWaterPlacement(BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> callbackInfo) {
        Deque<Boolean> waterStack = VANILLAADJUST$TARGET_WAS_WATER.get();
        Deque<Boolean> sourceStack = VANILLAADJUST$TARGET_WAS_SOURCE_WATER.get();
        boolean targetWasWater = !waterStack.isEmpty() && waterStack.pop();
        boolean targetWasSourceWater = !sourceStack.isEmpty() && sourceStack.pop();
        if (waterStack.isEmpty()) {
            VANILLAADJUST$TARGET_WAS_WATER.remove();
        }
        if (sourceStack.isEmpty()) {
            VANILLAADJUST$TARGET_WAS_SOURCE_WATER.remove();
        }

        if (callbackInfo.getReturnValueZ()) {
            PlayerPlacedWaterSourceHandler.handleDirectWaterPlacement(
                    (Level) (Object) this,
                    pos,
                    state,
                    targetWasWater,
                    targetWasSourceWater,
                    "direct water placement"
            );
        }
    }
}
