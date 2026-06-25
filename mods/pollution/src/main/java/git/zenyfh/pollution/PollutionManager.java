package git.zenyfh.pollution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import com.mojang.brigadier.arguments.DoubleArgumentType;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class PollutionManager {
    private static final boolean DEBUG_SERVER_SYNC = Boolean.getBoolean("pollution.debugServerSync");
    private static final boolean DEBUG_PERF = Boolean.getBoolean("pollution.debugPerf");
    private static final long SLOW_SOURCE_SCAN_WARN_MS = Long.getLong("pollution.slowSourceScanWarnMs", 100L);
    private static final long SOURCE_CACHE_TTL_TICKS = Long.getLong("pollution.sourceCacheTtlTicks", 1200L);
    private static final int MAX_SOURCE_CACHE_CHUNKS = Integer.getInteger("pollution.maxSourceCacheChunks", 8192);
    private static final Map<SourceCacheKey, CachedChunkSources> SOURCE_CACHE = new ConcurrentHashMap<>();
    private static final Perf PERF = new Perf();
    private final Map<UUID, Long> lastSyncedPlayerChunks = new HashMap<>();
    private int skippedPacketPlayersLastSync;

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (level.players().isEmpty()) {
            return;
        }

        if (!PollutionConfig.POLLUTION_ENABLED.get()) {
            if (level.getGameTime() % PollutionConfig.POLLUTION_SYNC_INTERVAL_TICKS.get() == 0L) {
                clearClients(level);
            }
            return;
        }

        long gameTime = level.getGameTime();
        if (gameTime % PollutionConfig.POLLUTION_SOURCE_SCAN_INTERVAL_TICKS.get() == 0L) {
            emitFromLoadedSources(level);
        }
        if (gameTime % PollutionConfig.POLLUTION_SIMULATION_INTERVAL_TICKS.get() == 0L) {
            simulateSpreadAndDecay(PollutionSavedData.get(level));
        }

        syncPlayersThatChangedChunk(level);
        if (gameTime % PollutionConfig.POLLUTION_SYNC_INTERVAL_TICKS.get() == 0L) {
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
            lastSyncedPlayerChunks.remove(player.getUUID());
            syncPlayer(player);
        }
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            lastSyncedPlayerChunks.remove(player.getUUID());
            syncPlayer(player);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        lastSyncedPlayerChunks.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("pollution")
                .requires(source -> source.hasPermission(2))
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
                            clearClients(level);
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
                        }))
                .then(Commands.literal("map")
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            PollutionSavedData data = PollutionSavedData.get(player.serverLevel());
                            ChunkPos center = player.chunkPosition();
                            int syncRadius = effectiveSyncRadius();
                            context.getSource().sendSuccess(() -> Component.literal(
                                    formatMap(data, center.x, center.z, Math.min(syncRadius, 8))
                                            + "\nEffective sync radius: " + syncRadius
                                            + " (configured " + PollutionConfig.POLLUTION_SYNC_RADIUS_CHUNKS.get() + ")"
                                            + ", effective visual radius: " + PollutionConfig.effectiveVisualRadiusChunks()
                                            + " (configured " + PollutionConfig.POLLUTION_VISUAL_RADIUS_CHUNKS.get() + ")"
                                            + ", effective edge fade chunks: " + PollutionConfig.effectiveGridEdgeFadeChunks()
                                            + "\nEffective source scan radius: " + PollutionConfig.effectiveSourceScanRadiusChunks()
                                            + " (configured " + PollutionConfig.POLLUTION_SOURCE_SCAN_RADIUS_CHUNKS.get() + ")"
                                            + ", skipped unsupported clients last sync: " + skippedPacketPlayersLastSync
                                            + "\nVisual source sync radius: " + PollutionConfig.effectiveVisualSourceSyncRadiusChunks()
                                            + ", max synced sources: " + PollutionConfig.effectiveMaxSyncedVisualSources()
                                            + ", source visual radius blocks: " + PollutionConfig.effectiveSourceVisualRadiusBlocks()
                            ), false);
                            return 1;
                        })));
    }

    private static void emitFromLoadedSources(ServerLevel level) {
        long startNanos = System.nanoTime();
        SourceScanResult result = scanLoadedSources(level);
        long elapsedNanos = System.nanoTime() - startNanos;
        PERF.recordSourceScan(result, elapsedNanos);
        if (elapsedNanos >= SLOW_SOURCE_SCAN_WARN_MS * 1_000_000L) {
            Pollution.LOGGER.warn(
                    "Pollution source scan slow level={} chunks={} blockEntities={} activeSources={} elapsed={}ms threshold={}ms",
                    level.dimension().location(),
                    result.scannedChunks,
                    result.scannedBlockEntities,
                    result.activeSources,
                    elapsedNanos / 1_000_000.0,
                    SLOW_SOURCE_SCAN_WARN_MS
            );
        }
        if (result.emissionByChunk.isEmpty()) {
            return;
        }
        PollutionSavedData data = PollutionSavedData.get(level);
        double intervalScale = PollutionConfig.POLLUTION_SOURCE_SCAN_INTERVAL_TICKS.get() / 100.0;
        for (Map.Entry<Long, Double> entry : result.emissionByChunk.entrySet()) {
            data.addPollution(entry.getKey(), entry.getValue() * intervalScale);
        }
        PERF.maybeLog();
    }

    private static SourceScanResult scanLoadedSources(ServerLevel level) {
        int radius = PollutionConfig.effectiveSourceScanRadiusChunks();
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
        result.scannedChunks++;
        for (BlockPos pos : potentialSources(level, chunk, chunkKey, result)) {
            BlockEntity blockEntity = chunk.getBlockEntity(pos);
            result.scannedBlockEntities++;
            if (blockEntity == null) {
                invalidateSourceCache(level.dimension(), chunkKey);
                continue;
            }
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
        long startNanos = System.nanoTime();
        Map<Long, Double> pollution = data.pollutionByChunk();
        if (pollution.isEmpty()) {
            return;
        }

        double spreadRate = PollutionConfig.POLLUTION_SPREAD_RATE.get();
        double diagonalWeight = PollutionConfig.POLLUTION_DIAGONAL_SPREAD_WEIGHT.get();
        double decayRate = PollutionConfig.POLLUTION_DECAY_RATE.get();
        double threshold = PollutionConfig.POLLUTION_STORAGE_THRESHOLD.get();
        double max = PollutionConfig.POLLUTION_MAX_PER_CHUNK.get();
        Map<Long, Double> deltas = new HashMap<>();

        for (Map.Entry<Long, Double> entry : new ArrayList<>(pollution.entrySet())) {
            long chunkKey = entry.getKey();
            double amount = entry.getValue();
            if (amount <= threshold) {
                continue;
            }
            double spread = amount * spreadRate;
            if (spread > 0.0) {
                int chunkX = ChunkPos.getX(chunkKey);
                int chunkZ = ChunkPos.getZ(chunkKey);
                double totalWeight = 4.0 + 4.0 * diagonalWeight;
                double cardinalShare = spread / totalWeight;
                double diagonalShare = cardinalShare * diagonalWeight;
                deltas.merge(chunkKey, -spread, Double::sum);
                addDelta(deltas, chunkX + 1, chunkZ, cardinalShare);
                addDelta(deltas, chunkX - 1, chunkZ, cardinalShare);
                addDelta(deltas, chunkX, chunkZ + 1, cardinalShare);
                addDelta(deltas, chunkX, chunkZ - 1, cardinalShare);
                addDelta(deltas, chunkX + 1, chunkZ + 1, diagonalShare);
                addDelta(deltas, chunkX + 1, chunkZ - 1, diagonalShare);
                addDelta(deltas, chunkX - 1, chunkZ + 1, diagonalShare);
                addDelta(deltas, chunkX - 1, chunkZ - 1, diagonalShare);
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
        PERF.recordDiffusion(System.nanoTime() - startNanos, pollution.size());
    }

    private static List<BlockPos> potentialSources(ServerLevel level, LevelChunk chunk, long chunkKey, SourceScanResult result) {
        SourceCacheKey key = new SourceCacheKey(level.dimension(), chunkKey);
        long gameTime = level.getGameTime();
        CachedChunkSources cached = SOURCE_CACHE.get(key);
        if (cached != null && gameTime - cached.gameTime <= SOURCE_CACHE_TTL_TICKS) {
            return cached.positions;
        }

        ArrayList<BlockPos> positions = new ArrayList<>();
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            result.scannedBlockEntities++;
            if (!blockEntity.isRemoved() && PollutionSourceDetector.isPotentialSource(blockEntity.getBlockState())) {
                positions.add(blockEntity.getBlockPos().immutable());
            }
        }
        if (SOURCE_CACHE.size() > MAX_SOURCE_CACHE_CHUNKS) {
            SOURCE_CACHE.clear();
        }
        List<BlockPos> immutable = positions.isEmpty() ? Collections.emptyList() : List.copyOf(positions);
        SOURCE_CACHE.put(key, new CachedChunkSources(gameTime, immutable));
        return immutable;
    }

    private static void invalidateSourceCache(ResourceKey<Level> dimension, long chunkKey) {
        SOURCE_CACHE.remove(new SourceCacheKey(dimension, chunkKey));
    }

    private static void addDelta(Map<Long, Double> deltas, int chunkX, int chunkZ, double amount) {
        if (amount != 0.0) {
            deltas.merge(ChunkPos.asLong(chunkX, chunkZ), amount, Double::sum);
        }
    }

    private void syncPlayersThatChangedChunk(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            long current = player.chunkPosition().toLong();
            Long previous = lastSyncedPlayerChunks.get(player.getUUID());
            if (previous == null || previous.longValue() != current) {
                syncPlayer(player);
            }
        }
    }

    private void syncPlayers(ServerLevel level) {
        skippedPacketPlayersLastSync = 0;
        for (ServerPlayer player : level.players()) {
            if (!syncPlayer(player)) {
                skippedPacketPlayersLastSync++;
            }
        }
        if (DEBUG_SERVER_SYNC) {
            Pollution.LOGGER.info(
                    "Pollution sync: players={} skippedUnsupported={} radius={}",
                    level.players().size(),
                    skippedPacketPlayersLastSync,
                    effectiveSyncRadius()
            );
        }
    }

    private boolean syncPlayer(ServerPlayer player) {
        if (!canReceivePollutionPackets(player)) {
            lastSyncedPlayerChunks.remove(player.getUUID());
            return false;
        }
        if (!PollutionConfig.POLLUTION_ENABLED.get()) {
            PacketDistributor.sendToPlayer(player, PollutionGridSyncPacket.clearPacket());
            return true;
        }
        PollutionSavedData data = PollutionSavedData.get(player.serverLevel());
        ChunkPos chunkPos = player.chunkPosition();
        int radius = effectiveSyncRadius();
        PollutionGridSyncPacket packet = buildGridPacket(player.serverLevel(), data, chunkPos.x, chunkPos.z, radius);
        PacketDistributor.sendToPlayer(player, packet);
        PERF.recordSync(packet);
        lastSyncedPlayerChunks.put(player.getUUID(), chunkPos.toLong());
        return true;
    }

    private void clearClients(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            if (canReceivePollutionPackets(player)) {
                PacketDistributor.sendToPlayer(player, PollutionGridSyncPacket.clearPacket());
            }
            lastSyncedPlayerChunks.remove(player.getUUID());
        }
    }

    private static PollutionGridSyncPacket buildGridPacket(ServerLevel level, PollutionSavedData data, int centerChunkX, int centerChunkZ, int radius) {
        int size = radius * 2 + 1;
        float[] values = new float[size * size];
        int index = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                values[index++] = (float) data.getPollution(centerChunkX + dx, centerChunkZ + dz);
            }
        }
        return new PollutionGridSyncPacket(centerChunkX, centerChunkZ, radius, false, values, collectVisualSources(level, data, centerChunkX, centerChunkZ));
    }

    private static PollutionGridSyncPacket.VisualSource[] collectVisualSources(ServerLevel level, PollutionSavedData data, int centerChunkX, int centerChunkZ) {
        int radius = PollutionConfig.effectiveVisualSourceSyncRadiusChunks();
        int maxSources = PollutionConfig.effectiveMaxSyncedVisualSources();
        if (maxSources <= 0) {
            return new PollutionGridSyncPacket.VisualSource[0];
        }

        ArrayList<PollutionGridSyncPacket.VisualSource> sources = new ArrayList<>(Math.min(maxSources, 32));
        for (int dz = -radius; dz <= radius && sources.size() < maxSources; dz++) {
            for (int dx = -radius; dx <= radius && sources.size() < maxSources; dx++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                for (BlockPos pos : potentialSources(level, chunk, chunk.getPos().toLong(), new SourceScanResult())) {
                    BlockEntity blockEntity = chunk.getBlockEntity(pos);
                    if (blockEntity == null) {
                        continue;
                    }
                    if (blockEntity.isRemoved()) {
                        continue;
                    }
                    double emission = PollutionSourceDetector.emissionRate(level, blockEntity);
                    if (emission <= 0.0) {
                        continue;
                    }
                    BlockPos sourcePos = blockEntity.getBlockPos();
                    sources.add(new PollutionGridSyncPacket.VisualSource(
                            sourcePos.getX(),
                            sourcePos.getY(),
                            sourcePos.getZ(),
                            (float) emission,
                            (float) data.getPollution(chunkX, chunkZ),
                            (byte) 0
                    ));
                    if (sources.size() >= maxSources) {
                        break;
                    }
                }
            }
        }
        return sources.toArray(PollutionGridSyncPacket.VisualSource[]::new);
    }

    private static int effectiveSyncRadius() {
        return PollutionConfig.effectiveSyncRadiusChunks();
    }

    private static boolean canReceivePollutionPackets(ServerPlayer player) {
        return player.connection.hasChannel(PollutionGridSyncPacket.TYPE);
    }

    private static double weightedLocalPollution(PollutionSavedData data, int chunkX, int chunkZ) {
        double diagonalWeight = PollutionConfig.POLLUTION_DIAGONAL_SPREAD_WEIGHT.get();
        double total = data.getPollution(chunkX, chunkZ);
        double weight = 1.0;
        total += 0.5 * data.getPollution(chunkX + 1, chunkZ);
        total += 0.5 * data.getPollution(chunkX - 1, chunkZ);
        total += 0.5 * data.getPollution(chunkX, chunkZ + 1);
        total += 0.5 * data.getPollution(chunkX, chunkZ - 1);
        weight += 2.0;
        total += 0.5 * diagonalWeight * data.getPollution(chunkX + 1, chunkZ + 1);
        total += 0.5 * diagonalWeight * data.getPollution(chunkX + 1, chunkZ - 1);
        total += 0.5 * diagonalWeight * data.getPollution(chunkX - 1, chunkZ + 1);
        total += 0.5 * diagonalWeight * data.getPollution(chunkX - 1, chunkZ - 1);
        weight += 2.0 * diagonalWeight;
        return total / weight;
    }

    private static String formatMap(PollutionSavedData data, int centerChunkX, int centerChunkZ, int radius) {
        StringBuilder builder = new StringBuilder("Pollution map around chunk ")
                .append(centerChunkX)
                .append(',')
                .append(centerChunkZ);
        for (int dz = -radius; dz <= radius; dz++) {
            builder.append('\n');
            for (int dx = -radius; dx <= radius; dx++) {
                builder.append(String.format("%7.1f", data.getPollution(centerChunkX + dx, centerChunkZ + dz)));
            }
        }
        return builder.toString();
    }

    private static final class SourceScanResult {
        private final Map<Long, Double> emissionByChunk = new HashMap<>();
        private int scannedChunks;
        private int scannedBlockEntities;
        private int activeSources;
        private double totalEmission;
    }

    private record SourceCacheKey(ResourceKey<Level> dimension, long chunkKey) {
    }

    private record CachedChunkSources(long gameTime, List<BlockPos> positions) {
    }

    private static final class Perf {
        private final LongAdder sourceScans = new LongAdder();
        private final LongAdder scannedChunks = new LongAdder();
        private final LongAdder scannedBlockEntities = new LongAdder();
        private final LongAdder activeSources = new LongAdder();
        private final LongAdder sourceScanNanos = new LongAdder();
        private final LongAdder diffusionRuns = new LongAdder();
        private final LongAdder diffusionNanos = new LongAdder();
        private final LongAdder syncPackets = new LongAdder();
        private final LongAdder syncBytesApprox = new LongAdder();

        void recordSourceScan(SourceScanResult result, long nanos) {
            if (!DEBUG_PERF) {
                return;
            }
            sourceScans.increment();
            scannedChunks.add(result.scannedChunks);
            scannedBlockEntities.add(result.scannedBlockEntities);
            activeSources.add(result.activeSources);
            sourceScanNanos.add(nanos);
        }

        void recordDiffusion(long nanos, int storedChunks) {
            if (!DEBUG_PERF) {
                return;
            }
            diffusionRuns.increment();
            diffusionNanos.add(nanos);
        }

        void recordSync(PollutionGridSyncPacket packet) {
            if (!DEBUG_PERF) {
                return;
            }
            syncPackets.increment();
            syncBytesApprox.add(17L + packet.values().length * 4L + packet.sources().length * 21L);
        }

        void maybeLog() {
            if (!DEBUG_PERF) {
                return;
            }
            long scans = sourceScans.sum();
            if (scans <= 0 || scans % 20 != 0) {
                return;
            }
            Pollution.LOGGER.info(
                    "Pollution perf sourceScans={} scannedChunks={} scannedBlockEntities={} activeSources={} avgSourceScanMs={} diffusionRuns={} avgDiffusionMs={} syncPackets={} approxSyncBytes={}",
                    scans,
                    scannedChunks.sum(),
                    scannedBlockEntities.sum(),
                    activeSources.sum(),
                    sourceScanNanos.sum() / scans / 1_000_000.0,
                    diffusionRuns.sum(),
                    diffusionRuns.sum() == 0 ? 0.0 : diffusionNanos.sum() / diffusionRuns.sum() / 1_000_000.0,
                    syncPackets.sum(),
                    syncBytesApprox.sum()
            );
        }
    }
}
