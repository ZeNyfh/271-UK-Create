package git.zenyfh.vanilla_adjustments;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

public final class BeneficialEffectHungerHandler {
    private static final int TICKS_PER_SECOND = 20;

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) {
            return;
        }
        if (!VanillaAdjustConfig.BENEFICIAL_EFFECT_HUNGER_DRAIN_ENABLED.get()) {
            return;
        }
        if (!player.isAlive() || player.isCreative() || player.isSpectator()) {
            return;
        }
        if (player.tickCount % TICKS_PER_SECOND != 0) {
            return;
        }

        int totalBuffStrength = totalBeneficialEffectStrength(player);
        if (totalBuffStrength <= 0) {
            return;
        }

        float exhaustionPerSecondPerStrength = VanillaAdjustConfig.BENEFICIAL_EFFECT_HUNGER_EXHAUSTION_PER_SECOND_PER_STRENGTH.get().floatValue();
        float extraExhaustion = exhaustionPerSecondPerStrength * totalBuffStrength;
        if (extraExhaustion > 0.0F) {
            player.causeFoodExhaustion(extraExhaustion);
        }
    }

    private static int totalBeneficialEffectStrength(Player player) {
        int total = 0;
        for (MobEffectInstance effect : player.getActiveEffects()) {
            if (!effect.getEffect().value().isBeneficial()) {
                continue;
            }
            total += effect.getAmplifier() + 1;
        }
        return total;
    }
}
