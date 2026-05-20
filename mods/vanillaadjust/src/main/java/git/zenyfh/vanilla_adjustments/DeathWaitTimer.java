package git.zenyfh.vanilla_adjustments;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

public final class DeathWaitTimer {
    private static final long WAIT_MILLIS = 5L * 60L * 1000L;
    private static final String DATA_KEY = Vanilla_adjustments.MODID;
    private static final String RESPAWN_UNLOCK_EPOCH_MS_KEY = "RespawnUnlockEpochMs";

    private DeathWaitTimer() {
    }

    public static void recordDeath(ServerPlayer player) {
        modData(player).putLong(RESPAWN_UNLOCK_EPOCH_MS_KEY, System.currentTimeMillis() + WAIT_MILLIS);
    }

    public static boolean isRespawnBlocked(ServerPlayer player) {
        long unlockEpochMs = getRespawnUnlockEpochMs(player);
        return player.getHealth() <= 0.0F && unlockEpochMs > System.currentTimeMillis();
    }

    public static void clearIfUnlocked(ServerPlayer player) {
        long unlockEpochMs = getRespawnUnlockEpochMs(player);
        if (unlockEpochMs > 0L && unlockEpochMs <= System.currentTimeMillis()) {
            modData(player).remove(RESPAWN_UNLOCK_EPOCH_MS_KEY);
        }
    }

    public static void notifyRespawnBlocked(ServerPlayer player) {
        long remainingSeconds = Math.max(1L, (getRespawnUnlockEpochMs(player) - System.currentTimeMillis() + 999L) / 1000L);
        long minutes = remainingSeconds / 60L;
        long seconds = remainingSeconds % 60L;
        player.displayClientMessage(Component.literal("You can respawn in %d:%02d.".formatted(minutes, seconds)), true);
    }

    private static long getRespawnUnlockEpochMs(ServerPlayer player) {
        return modData(player).getLong(RESPAWN_UNLOCK_EPOCH_MS_KEY);
    }

    private static CompoundTag modData(ServerPlayer player) {
        CompoundTag persistentData = player.getPersistentData();
        CompoundTag persistedPlayerData = persistentData.getCompound(Player.PERSISTED_NBT_TAG);
        persistentData.put(Player.PERSISTED_NBT_TAG, persistedPlayerData);

        CompoundTag modData = persistedPlayerData.getCompound(DATA_KEY);
        persistedPlayerData.put(DATA_KEY, modData);
        return modData;
    }
}
