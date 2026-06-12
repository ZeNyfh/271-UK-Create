package git.zenyfh.vanilla_adjustments;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;

public final class DeathWaitTimer {
    private static final long WAIT_MILLIS = 5L * 60L * 1000L;
    private static final int WAIT_TICKS = (int) (WAIT_MILLIS / 50L);
    private static final String DATA_KEY = Vanilla_adjustments.MODID;
    private static final String RESPAWN_UNLOCK_EPOCH_MS_KEY = "RespawnUnlockEpochMs";

    private DeathWaitTimer() {
    }

    public static void recordDeath(ServerPlayer player) {
        long unlockEpochMs = System.currentTimeMillis() + WAIT_MILLIS;
        modData(player).putLong(RESPAWN_UNLOCK_EPOCH_MS_KEY, unlockEpochMs);
        syncTimer(player, unlockEpochMs);
    }

    public static boolean isRespawnBlocked(ServerPlayer player) {
        long unlockEpochMs = getRespawnUnlockEpochMs(player);
        return player.getHealth() <= 0.0F && unlockEpochMs > System.currentTimeMillis();
    }

    public static void clearIfUnlocked(ServerPlayer player) {
        long unlockEpochMs = getRespawnUnlockEpochMs(player);
        if (unlockEpochMs > 0L && unlockEpochMs <= System.currentTimeMillis()) {
            modData(player).remove(RESPAWN_UNLOCK_EPOCH_MS_KEY);
            clearClientTimer(player);
        }
    }

    public static void notifyRespawnBlocked(ServerPlayer player) {
        syncTimer(player, getRespawnUnlockEpochMs(player));
    }

    private static void syncTimer(ServerPlayer player, long unlockEpochMs) {
        long remainingMillis = Math.max(0L, unlockEpochMs - System.currentTimeMillis());
        int remainingTicks = (int) Math.min(Integer.MAX_VALUE, (remainingMillis + 49L) / 50L);
        PacketDistributor.sendToPlayer(player, new DeathTimerSyncPacket(remainingTicks > 0, remainingTicks, WAIT_TICKS));
    }

    private static void clearClientTimer(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, DeathTimerSyncPacket.clear());
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
