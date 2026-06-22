package git.zenyfh.vanilla_adjustments.mixin;

import git.zenyfh.vanilla_adjustments.PlayerPlacedWaterSourceHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IceBlock.class)
public abstract class IceBlockMixin {
    @Unique
    private static final ThreadLocal<Boolean> VANILLAADJUST$ICE_WAS_NON_NATURAL = ThreadLocal.withInitial(() -> false);

    @Inject(method = "melt", at = @At("HEAD"))
    private void vanillaadjust$captureMeltOrigin(BlockState state, Level level, BlockPos pos, CallbackInfo callbackInfo) {
        VANILLAADJUST$ICE_WAS_NON_NATURAL.set(level instanceof ServerLevel serverLevel
                && PlayerPlacedWaterSourceHandler.isNonNaturalIce(serverLevel, pos));
    }

    @Inject(method = "melt", at = @At("RETURN"))
    private void vanillaadjust$propagateMeltOrigin(BlockState state, Level level, BlockPos pos, CallbackInfo callbackInfo) {
        try {
            PlayerPlacedWaterSourceHandler.handleIceThaw(level, pos, VANILLAADJUST$ICE_WAS_NON_NATURAL.get());
        } finally {
            VANILLAADJUST$ICE_WAS_NON_NATURAL.remove();
        }
    }

    @Inject(method = "playerDestroy", at = @At("HEAD"))
    private void vanillaadjust$captureBrokenIceOrigin(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity, ItemStack tool, CallbackInfo callbackInfo) {
        VANILLAADJUST$ICE_WAS_NON_NATURAL.set(level instanceof ServerLevel serverLevel
                && PlayerPlacedWaterSourceHandler.isNonNaturalIce(serverLevel, pos));
    }

    @Inject(method = "playerDestroy", at = @At("RETURN"))
    private void vanillaadjust$propagateBrokenIceOrigin(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity, ItemStack tool, CallbackInfo callbackInfo) {
        try {
            PlayerPlacedWaterSourceHandler.handleIceThaw(level, pos, VANILLAADJUST$ICE_WAS_NON_NATURAL.get());
        } finally {
            VANILLAADJUST$ICE_WAS_NON_NATURAL.remove();
        }
    }
}
