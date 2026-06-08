package git.zenyfh.vanilla_adjustments;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;

public final class EnderPearlRadiusLimiter {
    private static final String DATA_KEY = Vanilla_adjustments.MODID;
    private static final String ORIGIN_X_KEY = "pearl_origin_x";
    private static final String ORIGIN_Y_KEY = "pearl_origin_y";
    private static final String ORIGIN_Z_KEY = "pearl_origin_z";
    private static final String ORIGIN_DIMENSION_KEY = "pearl_origin_dimension";

    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!VanillaAdjustConfig.ENDER_PEARL_RADIUS_LIMIT_ENABLED.get()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(event.getEntity() instanceof ThrownEnderpearl pearl)) {
            return;
        }
        if (!(pearl.getOwner() instanceof ServerPlayer)) {
            return;
        }

        CompoundTag data = modData(pearl);
        if (hasOrigin(data)) {
            return;
        }

        data.putDouble(ORIGIN_X_KEY, pearl.getX());
        data.putDouble(ORIGIN_Y_KEY, pearl.getY());
        data.putDouble(ORIGIN_Z_KEY, pearl.getZ());
        data.putString(ORIGIN_DIMENSION_KEY, serverLevel.dimension().location().toString());
    }

    @SubscribeEvent
    public void onEnderPearlTeleport(EntityTeleportEvent.EnderPearl event) {
        if (!VanillaAdjustConfig.ENDER_PEARL_RADIUS_LIMIT_ENABLED.get()) {
            return;
        }
        if (VanillaAdjustConfig.ENDER_PEARL_LIMIT_BEHAVIOUR.get() != VanillaAdjustConfig.EnderPearlLimitBehaviour.CANCEL) {
            return;
        }

        ThrownEnderpearl pearl = event.getPearlEntity();
        CompoundTag data = modData(pearl);
        ServerPlayer player = event.getPlayer();
        boolean blocked = !hasOrigin(data) || !isSameDimension(data, pearl) || exceedsLimit(data, event);
        if (!blocked) {
            return;
        }

        event.setCanceled(true);
        if (VanillaAdjustConfig.ENDER_PEARL_LIMIT_SEND_MESSAGE.get()) {
            player.displayClientMessage(Component.literal("Ender pearl exceeded the " + VanillaAdjustConfig.ENDER_PEARL_MAX_RADIUS.get() + " block limit."), true);
        }
    }

    private static boolean exceedsLimit(CompoundTag data, EntityTeleportEvent.EnderPearl event) {
        double dx = event.getTargetX() - data.getDouble(ORIGIN_X_KEY);
        double dy = event.getTargetY() - data.getDouble(ORIGIN_Y_KEY);
        double dz = event.getTargetZ() - data.getDouble(ORIGIN_Z_KEY);
        double distanceSq = dx * dx + dz * dz;
        if (!VanillaAdjustConfig.ENDER_PEARL_LIMIT_USES_HORIZONTAL_DISTANCE.get()) {
            distanceSq += dy * dy;
        }
        double maxRadius = VanillaAdjustConfig.ENDER_PEARL_MAX_RADIUS.get();
        return distanceSq > maxRadius * maxRadius;
    }

    private static boolean isSameDimension(CompoundTag data, ThrownEnderpearl pearl) {
        String originDimension = data.getString(ORIGIN_DIMENSION_KEY);
        return !originDimension.isEmpty() && originDimension.equals(pearl.level().dimension().location().toString());
    }

    private static boolean hasOrigin(CompoundTag data) {
        return data.contains(ORIGIN_X_KEY) && data.contains(ORIGIN_Y_KEY) && data.contains(ORIGIN_Z_KEY) && data.contains(ORIGIN_DIMENSION_KEY);
    }

    private static CompoundTag modData(ThrownEnderpearl pearl) {
        CompoundTag persistentData = pearl.getPersistentData();
        CompoundTag modData = persistentData.getCompound(DATA_KEY);
        persistentData.put(DATA_KEY, modData);
        return modData;
    }
}
