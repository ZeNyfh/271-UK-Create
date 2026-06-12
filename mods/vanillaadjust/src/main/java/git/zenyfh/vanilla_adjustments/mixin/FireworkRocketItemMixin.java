package git.zenyfh.vanilla_adjustments.mixin;

import git.zenyfh.vanilla_adjustments.VanillaAdjustConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FireworkRocketItem.class)
public abstract class FireworkRocketItemMixin {
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void vanillaadjust$disableElytraRocketBoost(Level level, Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResultHolder<ItemStack>> callbackInfo) {
        if (level.isClientSide || !VanillaAdjustConfig.DISABLE_ELYTRA_ROCKET_BOOST.get() || !player.isFallFlying()) {
            return;
        }

        ItemStack stack = player.getItemInHand(hand);
        if (VanillaAdjustConfig.DISABLE_ELYTRA_ROCKET_BOOST_SEND_MESSAGE.get()) {
            player.displayClientMessage(Component.translatable("message.vanilla_adjustments.elytra_rocket_boost_disabled"), true);
        }

        callbackInfo.setReturnValue(InteractionResultHolder.fail(stack));
    }
}
