package git.zenyfh.vanilla_adjustments;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.mojang.brigadier.arguments.DoubleArgumentType;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class PollutionManager {
    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (!VanillaAdjustConfig.POLLUTION_ENABLED.get() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        long gameTime = level.getGameTime();
        if (gameTime % VanillaAdjustConfig.POLLUTION_SOURCE_SCAN_INTERVAL_TICKS.get() == 0L) {
            emitFromLoadedSources(level);
        }
        if (gameTime % VanillaAdjustConfig.POLLUTION_SIMULATION_INTERVAL_TICKS.get() == 0L) {
            simulateSpreadAndDecay(PollutionSavedData.get(level));
        }
        if (gameTime % VanillaAdjustConfig.POLLUTION_SYNC_INTERVAL_TICKS.get() == 0L) {
            syncPlayers(level);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncPlayer(player);
        }
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncPlayer(player);
        }
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncPlayer(player);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("vanillaadjust")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("pollution")
                        .then(Commands.literal("get")
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                    PollutionSavedData data = PollutionSavedData.get(player.serverLevel());
                                    ChunkPos chunkPos = player.chunkPosition();
                                    double local = data.getPollution(chunkPos.x, chunkPos.z);
                                    double visual = weightedLocalPollution(data, chunkPos.x, chunkPos.z);
                                    context.getSource().sendSuccess(() -> Component.literal(String.format(
                                            "Pollution: chunk=%.2f visual=%.2f storedChunks=%d",
                                            local,
                                            visual,
                                            data.pollutionByChunk().size()
                                    )), false);
                                    return 1;
                                }))
                        .then(Commands.literal("set")
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.0))
                                        .executes(context -> {
                                            ServerPlayer player = context.getSource().getPlayerOrException();
                                            PollutionSavedData data = PollutionSavedData.get(player.serverLevel());
                                            ChunkPos chunkPos = player.chunkPosition();
                                            data.setPollution(chunkPos.toLong(), DoubleArgumentType.getDouble(context, "amount"));
                                            syncPlayer(player);
                                            context.getSource().sendSuccess(() -> Component.literal("Set pollution in current chunk."), true);
                                            return 1;
                                        })))
                        .then(Commands.literal("clear")
                                .executes(context -> {
                                    ServerLevel level = context.getSource().getLevel();
                                    PollutionSavedData.get(level).clear();
                                    syncPlayers(level);
                                    context.getSource().sendSuccess(() -> Component.literal("Cleared pollution in this dimension."), true);
                                    return 1;
                                }))
                        .then(Commands.literal("sources")
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                    SourceScanResult result = scanLoadedSources(player.serverLevel());
                                    context.getSource().sendSuccess(() -> Component.literal(String.format(
                                            "Active pollution sources near players: %d, emission this scan: %.2f",
                                            result.activeSources,
                                            result.totalEmission
                                    )), false);
                                    return result.activeSources;
                                }))));
    }

    private static void emitFromLoadedSources(ServerLevel level) {
        SourceScanResult result = scanLoadedSources(level);
        if (result.emissionByChunk.isEmpty()) {
            return;
        }
        PollutionSavedData data = PollutionSavedData.get(level);
        double intervalScale = VanillaAdjustConfig.POLLUTION_SOURCE_SCAN_INTERVAL_TICKS.get() / 100.0;
        for (Map.Entry<Long, Double> entry : result.emissionByChunk.entrySet()) {
            data.addPollution(entry.getKey(), entry.getValue() * intervalScale);
        }
    }

    private static SourceScanResult scanLoadedSources(ServerLevel level) {
        int radius = VanillaAdjustConfig.POLLUTION_SOURCE_SCAN_RADIUS_CHUNKS.get();
        Set<Long> visitedChunks = new HashSet<>();
        SourceScanResult result = new SourceScanResult();
        for (ServerPlayer player : level.players()) {
            ChunkPos center = player.chunkPosition();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int chunkX = center.x + dx;
                    int chunkZ = center.z + dz;
                    long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
                    if (!visitedChunks.add(chunkKey)) {
                        continue;
                    }
                    LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                    if (chunk != null) {
                        scanChunk(level, chunk, chunkKey, result);
                    }
                }
            }
        }
        return result;
    }

    private static void scanChunk(ServerLevel level, LevelChunk chunk, long chunkKey, SourceScanResult result) {
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            if (blockEntity.isRemoved()) {
                continue;
            }
            double emission = PollutionSourceDetector.emissionRate(level, blockEntity);
            if (emission > 0.0) {
                result.activeSources++;
                result.totalEmission += emission;
                result.emissionByChunk.merge(chunkKey, emission, Double::sum);
            }
        }
    }

    private static void simulateSpreadAndDecay(PollutionSavedData data) {
        Map<Long, Double> pollution = data.pollutionByChunk();
        if (pollution.isEmpty()) {
            return;
        }

        double spreadRate = VanillaAdjustConfig.POLLUTION_SPREAD_RATE.get();
        double decayRate = VanillaAdjustConfig.POLLUTION_DECAY_RATE.get();
        double threshold = VanillaAdjustConfig.POLLUTION_STORAGE_THRESHOLD.get();
        double max = VanillaAdjustConfig.POLLUTION_MAX_PER_CHUNK.get();
        Map<Long, Double> deltas = new HashMap<>();

        for (Map.Entry<Long, Double> entry : new ArrayList<>(pollution.entrySet())) {
            long chunkKey = entry.getKey();
            double amount = entry.getValue();
            if (amount <= threshold) {
                continue;
            }
            double spread = amount * spreadRate;
            if (spread > 0.0) {
                double each = spread / 4.0;
                int chunkX = ChunkPos.getX(chunkKey);
                int chunkZ = ChunkPos.getZ(chunkKey);
                deltas.merge(chunkKey, -spread, Double::sum);
                deltas.merge(ChunkPos.asLong(chunkX + 1, chunkZ), each, Double::sum);
                deltas.merge(ChunkPos.asLong(chunkX - 1, chunkZ), each, Double::sum);
                deltas.merge(ChunkPos.asLong(chunkX, chunkZ + 1), each, Double::sum);
                deltas.merge(ChunkPos.asLong(chunkX, chunkZ - 1), each, Double::sum);
            }
        }

        for (Map.Entry<Long, Double> delta : deltas.entrySet()) {
            pollution.merge(delta.getKey(), delta.getValue(), Double::sum);
        }

        boolean changed = !deltas.isEmpty();
        for (Map.Entry<Long, Double> entry : new ArrayList<>(pollution.entrySet())) {
            double amount = Math.max(0.0, Math.min(entry.getValue(), max));
            amount *= 1.0 - decayRate;
            if (amount < threshold) {
                pollution.remove(entry.getKey());
                changed = true;
            } else if (Math.abs(amount - entry.getValue()) > 0.000_001) {
                pollution.put(entry.getKey(), amount);
                changed = true;
            }
        }
        if (changed) {
            data.setDirty();
        }
    }

    private static void syncPlayers(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            syncPlayer(player);
        }
    }

    private static void syncPlayer(ServerPlayer player) {
        if (!VanillaAdjustConfig.POLLUTION_ENABLED.get()) {
            PacketDistributor.sendToPlayer(player, new PollutionSyncPacket(0.0F));
            return;
        }
        PollutionSavedData data = PollutionSavedData.get(player.serverLevel());
        ChunkPos chunkPos = player.chunkPosition();
        float visual = (float) weightedLocalPollution(data, chunkPos.x, chunkPos.z);
        PacketDistributor.sendToPlayer(player, new PollutionSyncPacket(visual));
    }

    private static double weightedLocalPollution(PollutionSavedData data, int chunkX, int chunkZ) {
        double total = data.getPollution(chunkX, chunkZ);
        double weight = 1.0;
        total += 0.5 * data.getPollution(chunkX + 1, chunkZ);
        total += 0.5 * data.getPollution(chunkX - 1, chunkZ);
        total += 0.5 * data.getPollution(chunkX, chunkZ + 1);
        total += 0.5 * data.getPollution(chunkX, chunkZ - 1);
        weight += 2.0;
        total += 0.25 * data.getPollution(chunkX + 1, chunkZ + 1);
        total += 0.25 * data.getPollution(chunkX + 1, chunkZ - 1);
        total += 0.25 * data.getPollution(chunkX - 1, chunkZ + 1);
        total += 0.25 * data.getPollution(chunkX - 1, chunkZ - 1);
        weight += 1.0;
        return total / weight;
    }

    private static final class SourceScanResult {
        private final Map<Long, Double> emissionByChunk = new HashMap<>();
        private int activeSources;
        private double totalEmission;
    }
}
