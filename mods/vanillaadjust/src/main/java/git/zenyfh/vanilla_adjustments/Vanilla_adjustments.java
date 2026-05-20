package git.zenyfh.vanilla_adjustments;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

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

    private static boolean isPiglinFamily(LivingEntity entity) {
        EntityType<?> type = entity.getType();
        return type == EntityType.PIGLIN || type == EntityType.PIGLIN_BRUTE || type == EntityType.ZOMBIFIED_PIGLIN;
    }
}
