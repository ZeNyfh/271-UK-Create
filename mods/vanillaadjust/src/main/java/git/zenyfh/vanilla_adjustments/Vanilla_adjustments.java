package git.zenyfh.vanilla_adjustments;

import net.minecraft.tags.ItemTags;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

@Mod(Vanilla_adjustments.MODID)
public class Vanilla_adjustments {
    public static final String MODID = "vanilla_adjustments";

    public Vanilla_adjustments() {
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onLivingDrops(LivingDropsEvent event) {
        LivingEntity entity = event.getEntity();

        if (isPiglinFamily(entity)) {
            event.getDrops().removeIf(drop -> drop.getItem().is(ItemTags.PIGLIN_LOVED));
        }
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DeathWaitTimer.recordDeath(player);
        }
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DeathWaitTimer.clearIfUnlocked(player);
        }
    }

    private static boolean isPiglinFamily(LivingEntity entity) {
        EntityType<?> type = entity.getType();
        return type == EntityType.PIGLIN || type == EntityType.PIGLIN_BRUTE || type == EntityType.ZOMBIFIED_PIGLIN;
    }
}
