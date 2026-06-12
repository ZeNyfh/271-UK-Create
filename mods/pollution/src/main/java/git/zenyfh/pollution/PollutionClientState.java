package git.zenyfh.pollution;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

public final class PollutionClientState {
    private static final boolean DEBUG_CLIENT_LAYERS = Boolean.getBoolean("pollution.debugClientLayers");
    private static final Map<Long, ClientPollutionCell> clientPollutionByChunk = new HashMap<>();
    private static final Map<Long, ClientVisualSource> knownVisualSources = new HashMap<>();
    private static final Queue<Long> pendingPollutionRerenders = new ArrayDeque<>();
    private static final Set<Long> queuedPollutionRerenders = new HashSet<>();
    private static volatile PollutionRenderSnapshot renderSnapshot = PollutionRenderSnapshot.EMPTY;

    private static long clientGameTime;
    private static int lastServerCenterChunkX;
    private static int lastServerCenterChunkZ;
    private static int lastServerGridRadius;
    private static float targetLocalPollution;
    private static float displayLocalPollution;
    private static float targetFogPollution;
    private static volatile float currentFogPollution;
    private static volatile boolean pollutionFogActive;
    private static boolean renderSnapshotDirty;
    private static boolean serverSyncActive;
    private static long lastServerPacketGameTime = Long.MIN_VALUE;
    private static long lastDebugLogGameTime;

    private PollutionClientState() {
    }

    public static void receive(PollutionGridSyncPacket packet) {
        if (packet.clear()) {
            queueAllCachedChunksForRerender();
            clear();
            return;
        }

        int expectedLength = gridSize(packet.radius()) * gridSize(packet.radius());
        if (packet.radius() < 0 || packet.values().length != expectedLength) {
            return;
        }

        serverSyncActive = true;
        lastServerPacketGameTime = clientGameTime;
        lastServerCenterChunkX = packet.centerChunkX();
        lastServerCenterChunkZ = packet.centerChunkZ();
        lastServerGridRadius = packet.radius();
        int nonzeroServerCells = 0;
        int index = 0;
        for (int dz = -packet.radius(); dz <= packet.radius(); dz++) {
            for (int dx = -packet.radius(); dx <= packet.radius(); dx++) {
                float value = sanitize(packet.values()[index++]);
                if (value > 0.0F) {
                    nonzeroServerCells++;
                }
                updateServerCell(packet.centerChunkX() + dx, packet.centerChunkZ() + dz, value);
            }
        }

        for (PollutionGridSyncPacket.VisualSource source : packet.sources()) {
            updateVisualSource(source, true);
        }
        publishRenderSnapshotIfDirty();
        debugClientLayers("sync radius=" + packet.radius() + " nonzeroServerCells=" + nonzeroServerCells + " sources=" + packet.sources().length);
    }

    public static void clientTick(BlockPos playerPos) {
        clientGameTime++;
        tickCachedCells(playerPos);
        pruneVisualSources(playerPos);
        publishRenderSnapshotIfDirty();

        PollutionRenderSnapshot snapshot = renderSnapshot;
        targetLocalPollution = snapshot.sampleGrassPollutionAtBlock(playerPos);
        displayLocalPollution = snapshot.sampleDisplayPollutionAtBlock(playerPos);
        double epsilon = PollutionConfig.POLLUTION_VISUAL_EPSILON.get();
        if (targetLocalPollution < epsilon) {
            targetLocalPollution = 0.0F;
        }
        if (displayLocalPollution < epsilon) {
            displayLocalPollution = 0.0F;
        }

        targetFogPollution = displayLocalPollution;
        float fogRate = targetFogPollution > currentFogPollution
                ? PollutionConfig.POLLUTION_FOG_RISE_RATE.get().floatValue()
                : PollutionConfig.POLLUTION_FOG_FALL_RATE.get().floatValue();
        currentFogPollution = approach(currentFogPollution, targetFogPollution, fogRate, (float) PollutionConfig.POLLUTION_FOG_DISABLE_THRESHOLD.get().doubleValue());
        updateFogActive();

        if (DEBUG_CLIENT_LAYERS && clientGameTime - lastDebugLogGameTime >= 100L) {
            lastDebugLogGameTime = clientGameTime;
            debugClientLayers("tick grass=" + targetLocalPollution + " display=" + displayLocalPollution + " fog=" + currentFogPollution);
        }
    }

    public static float localDisplayPollution() {
        return displayLocalPollution;
    }

    public static float currentFogPollution() {
        return currentFogPollution;
    }

    public static boolean isPollutionFogActive() {
        return pollutionFogActive;
    }

    public static float sampleAtBlock(BlockPos pos) {
        return renderSnapshot.sampleGrassPollutionAtBlock(pos);
    }

    public static boolean shouldRunClientFallback() {
        if (!serverSyncActive) {
            return true;
        }
        if (clientGameTime - lastServerPacketGameTime > PollutionConfig.POLLUTION_SERVER_SYNC_TIMEOUT_TICKS.get()) {
            serverSyncActive = false;
            return true;
        }
        return PollutionConfig.POLLUTION_CLIENT_FALLBACK_WHEN_SERVER_SYNC_ACTIVE.get();
    }

    public static void recordClientVisualSource(BlockPos pos, float emissionRate, float localPollution) {
        updateVisualSource(new PollutionGridSyncPacket.VisualSource(pos.getX(), pos.getY(), pos.getZ(), emissionRate, localPollution, (byte) 1), false);
        updateLocalCell(Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16), localPollution);
    }

    public static Long pollRerenderChunk() {
        Long chunkKey = pendingPollutionRerenders.poll();
        if (chunkKey != null) {
            queuedPollutionRerenders.remove(chunkKey);
        }
        return chunkKey;
    }

    public static int cachedChunkCount() {
        return clientPollutionByChunk.size();
    }

    public static int nonzeroCachedChunkCount() {
        int count = 0;
        for (ClientPollutionCell cell : clientPollutionByChunk.values()) {
            if (Math.max(combinedTargetValue(cell), combinedDisplayValue(cell)) >= PollutionConfig.POLLUTION_CLIENT_CACHE_MIN_VALUE.get()) {
                count++;
            }
        }
        return count;
    }

    public static int knownVisualSourceCount() {
        return knownVisualSources.size();
    }

    public static int rerenderQueueSize() {
        return pendingPollutionRerenders.size();
    }

    public static int lastServerGridRadius() {
        return lastServerGridRadius;
    }

    public static void clear() {
        clientPollutionByChunk.clear();
        knownVisualSources.clear();
        lastServerCenterChunkX = 0;
        lastServerCenterChunkZ = 0;
        lastServerGridRadius = 0;
        targetLocalPollution = 0.0F;
        displayLocalPollution = 0.0F;
        targetFogPollution = 0.0F;
        currentFogPollution = 0.0F;
        pollutionFogActive = false;
        serverSyncActive = false;
        lastServerPacketGameTime = Long.MIN_VALUE;
        renderSnapshotDirty = false;
        renderSnapshot = PollutionRenderSnapshot.EMPTY;
    }

    private static void updateServerCell(int chunkX, int chunkZ, float serverTargetPollution) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        ClientPollutionCell cell = clientPollutionByChunk.get(key);
        if (cell == null) {
            if (serverTargetPollution <= 0.0F) {
                return;
            }
            cell = new ClientPollutionCell(chunkX, chunkZ);
            cell.serverTargetPollution = serverTargetPollution;
            cell.serverDisplayPollution = serverTargetPollution;
            cell.lastServerSeenGameTime = clientGameTime;
            cell.lastUpdatedGameTime = clientGameTime;
            clientPollutionByChunk.put(key, cell);
            queueRerenderWithMargin(chunkX, chunkZ);
            renderSnapshotDirty = true;
            return;
        }

        float oldCombined = combinedTargetValue(cell);
        cell.serverTargetPollution = serverTargetPollution;
        cell.lastServerSeenGameTime = clientGameTime;
        if (cell.serverDisplayPollution <= 0.0F && serverTargetPollution > 0.0F) {
            cell.serverDisplayPollution = serverTargetPollution;
        }
        cell.lastUpdatedGameTime = clientGameTime;
        queueRerenderIfCombinedChanged(cell, oldCombined, combinedTargetValue(cell));
        renderSnapshotDirty = true;
    }

    private static void updateLocalCell(int chunkX, int chunkZ, float localTargetPollution) {
        if (localTargetPollution <= 0.0F) {
            return;
        }

        long key = ChunkPos.asLong(chunkX, chunkZ);
        ClientPollutionCell cell = clientPollutionByChunk.get(key);
        if (cell == null) {
            cell = new ClientPollutionCell(chunkX, chunkZ);
            cell.localTargetPollution = localTargetPollution;
            cell.localDisplayPollution = localTargetPollution;
            cell.lastLocalSeenGameTime = clientGameTime;
            cell.lastUpdatedGameTime = clientGameTime;
            clientPollutionByChunk.put(key, cell);
            queueRerenderWithMargin(chunkX, chunkZ);
            renderSnapshotDirty = true;
            return;
        }

        float oldCombined = combinedTargetValue(cell);
        cell.localTargetPollution = Math.max(cell.localTargetPollution, localTargetPollution);
        if (cell.localDisplayPollution <= 0.0F) {
            cell.localDisplayPollution = cell.localTargetPollution;
        }
        cell.lastLocalSeenGameTime = clientGameTime;
        cell.lastUpdatedGameTime = clientGameTime;
        queueRerenderIfCombinedChanged(cell, oldCombined, combinedTargetValue(cell));
        renderSnapshotDirty = true;
    }

    private static void tickCachedCells(BlockPos playerPos) {
        if (playerPos == null) {
            return;
        }

        int playerChunkX = Math.floorDiv(playerPos.getX(), 16);
        int playerChunkZ = Math.floorDiv(playerPos.getZ(), 16);
        double epsilon = PollutionConfig.POLLUTION_VISUAL_EPSILON.get();
        float riseRate = PollutionConfig.POLLUTION_VISUAL_RISE_RATE.get().floatValue();
        float fallRate = PollutionConfig.POLLUTION_VISUAL_FALL_RATE.get().floatValue();
        boolean decayUnseen = PollutionConfig.POLLUTION_CLIENT_CACHE_DECAY_WHEN_UNSEEN.get();
        float unseenDecay = PollutionConfig.POLLUTION_CLIENT_UNSEEN_DECAY_RATE.get().floatValue();
        int cacheRadius = PollutionConfig.effectiveClientCacheRadiusChunks();
        int maxAgeTicks = PollutionConfig.POLLUTION_CLIENT_CACHE_MAX_AGE_TICKS.get();
        double minValue = PollutionConfig.POLLUTION_CLIENT_CACHE_MIN_VALUE.get();

        Iterator<Map.Entry<Long, ClientPollutionCell>> iterator = clientPollutionByChunk.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, ClientPollutionCell> entry = iterator.next();
            ClientPollutionCell cell = entry.getValue();
            float oldCombinedTarget = combinedTargetValue(cell);
            float oldCombinedDisplay = combinedDisplayValue(cell);
            int oldTargetLevel = visualGrassLevel(oldCombinedTarget);
            int oldDisplayLevel = visualGrassLevel(oldCombinedDisplay);

            if (decayUnseen && cell.lastLocalSeenGameTime >= 0L && clientGameTime > cell.lastLocalSeenGameTime) {
                cell.localTargetPollution *= Math.max(0.0F, 1.0F - unseenDecay);
            }

            cell.serverDisplayPollution = approachLayer(cell.serverDisplayPollution, cell.serverTargetPollution, riseRate, fallRate, (float) epsilon);
            cell.localDisplayPollution = approachLayer(cell.localDisplayPollution, cell.localTargetPollution, riseRate, fallRate, (float) epsilon);
            cell.lastUpdatedGameTime = clientGameTime;

            float newCombinedTarget = combinedTargetValue(cell);
            float newCombinedDisplay = combinedDisplayValue(cell);
            int newTargetLevel = visualGrassLevel(newCombinedTarget);
            int newDisplayLevel = visualGrassLevel(newCombinedDisplay);
            if (oldTargetLevel != newTargetLevel || oldDisplayLevel != newDisplayLevel) {
                queueRerenderWithMargin(cell.chunkX, cell.chunkZ);
            }
            if (oldTargetLevel != newTargetLevel
                    || oldDisplayLevel != newDisplayLevel
                    || Math.abs(oldCombinedTarget - newCombinedTarget) > 0.001F
                    || Math.abs(oldCombinedDisplay - newCombinedDisplay) > 0.001F) {
                renderSnapshotDirty = true;
            }

            int distance = Math.max(Math.abs(cell.chunkX - playerChunkX), Math.abs(cell.chunkZ - playerChunkZ));
            long lastSeen = Math.max(cell.lastServerSeenGameTime, cell.lastLocalSeenGameTime);
            boolean oldEnough = lastSeen >= 0L && clientGameTime - lastSeen > maxAgeTicks;
            boolean nearlyClean = Math.max(newCombinedTarget, newCombinedDisplay) < minValue;
            if (distance > cacheRadius && oldEnough && nearlyClean) {
                queueRerenderWithMargin(cell.chunkX, cell.chunkZ);
                iterator.remove();
                renderSnapshotDirty = true;
            }
        }
    }

    private static void updateVisualSource(PollutionGridSyncPacket.VisualSource source, boolean serverConfirmed) {
        long key = BlockPos.asLong(source.blockX(), source.blockY(), source.blockZ());
        ClientVisualSource visualSource = knownVisualSources.get(key);
        if (visualSource == null) {
            visualSource = new ClientVisualSource(source.blockX(), source.blockY(), source.blockZ());
            knownVisualSources.put(key, visualSource);
        }
        int oldLevel = visualSourceLevel(visualSource);
        visualSource.emissionRate = Math.max(visualSource.emissionRate, Math.max(0.0F, source.emissionRate()));
        visualSource.localPollution = Math.max(visualSource.localPollution, Math.max(0.0F, source.localPollution()));
        visualSource.sourceType = source.sourceType();
        visualSource.serverConfirmed |= serverConfirmed;
        visualSource.lastSeenGameTime = clientGameTime;
        int newLevel = visualSourceLevel(visualSource);
        if (oldLevel != newLevel || newLevel > 0) {
            queueRerenderWithMargin(Math.floorDiv(source.blockX(), 16), Math.floorDiv(source.blockZ(), 16));
        }
        renderSnapshotDirty = true;
    }

    private static void pruneVisualSources(BlockPos playerPos) {
        if (playerPos == null) {
            return;
        }
        int playerChunkX = Math.floorDiv(playerPos.getX(), 16);
        int playerChunkZ = Math.floorDiv(playerPos.getZ(), 16);
        int cacheRadius = PollutionConfig.effectiveClientCacheRadiusChunks();
        int holdTicks = PollutionConfig.POLLUTION_VISUAL_SOURCE_HOLD_TICKS.get();
        double minValue = PollutionConfig.POLLUTION_CLIENT_CACHE_MIN_VALUE.get();
        float unseenDecay = PollutionConfig.POLLUTION_VISUAL_SOURCE_UNSEEN_DECAY_RATE.get().floatValue();
        Iterator<ClientVisualSource> iterator = knownVisualSources.values().iterator();
        while (iterator.hasNext()) {
            ClientVisualSource source = iterator.next();
            int sourceChunkX = Math.floorDiv(source.blockX, 16);
            int sourceChunkZ = Math.floorDiv(source.blockZ, 16);
            int distance = Math.max(Math.abs(sourceChunkX - playerChunkX), Math.abs(sourceChunkZ - playerChunkZ));
            long age = clientGameTime - source.lastSeenGameTime;
            int oldLevel = visualSourceLevel(source);
            if (age > 0) {
                float decay = Math.max(0.0F, 1.0F - unseenDecay);
                source.localPollution *= decay;
                source.emissionRate *= decay;
            }
            int newLevel = visualSourceLevel(source);
            if (oldLevel != newLevel) {
                queueRerenderWithMargin(sourceChunkX, sourceChunkZ);
            }
            if (age > 0 && (source.localPollution > 0.0F || source.emissionRate > 0.0F)) {
                renderSnapshotDirty = true;
            }
            boolean farBeyondCache = distance > cacheRadius && age > holdTicks;
            boolean visuallyGone = visualSourceStrength(source) < minValue;
            if (farBeyondCache || (age > holdTicks && visuallyGone)) {
                queueRerenderWithMargin(sourceChunkX, sourceChunkZ);
                iterator.remove();
                renderSnapshotDirty = true;
            }
        }
    }

    private static void queueAllCachedChunksForRerender() {
        if (!PollutionConfig.POLLUTION_RERENDER_ENABLED.get()) {
            return;
        }
        for (ClientPollutionCell cell : clientPollutionByChunk.values()) {
            if (visualGrassLevel(combinedTargetValue(cell)) > 0 || visualGrassLevel(combinedDisplayValue(cell)) > 0) {
                queueRerenderWithMargin(cell.chunkX, cell.chunkZ);
            }
        }
        for (ClientVisualSource source : knownVisualSources.values()) {
            queueRerenderWithMargin(Math.floorDiv(source.blockX, 16), Math.floorDiv(source.blockZ, 16));
        }
    }

    private static void queueRerenderIfCombinedChanged(ClientPollutionCell cell, float oldValue, float newValue) {
        if (Math.abs(newValue - oldValue) >= PollutionConfig.POLLUTION_RERENDER_THRESHOLD.get()
                || visualGrassLevel(oldValue) != visualGrassLevel(newValue)) {
            queueRerenderWithMargin(cell.chunkX, cell.chunkZ);
        }
    }

    private static int visualGrassLevel(float pollution) {
        double epsilon = PollutionConfig.POLLUTION_VISUAL_EPSILON.get();
        if (pollution < epsilon) {
            return 0;
        }
        double normalized = Math.max(0.0, Math.min(1.0, pollution / PollutionConfig.POLLUTION_GRASS_TINT_MAX.get()));
        if (normalized < 0.25) {
            return 1;
        }
        if (normalized < 0.50) {
            return 2;
        }
        if (normalized < 0.75) {
            return 3;
        }
        return 4;
    }

    private static int visualSourceLevel(ClientVisualSource source) {
        return visualGrassLevel(visualSourceStrength(source));
    }

    private static float visualSourceStrength(ClientVisualSource source) {
        return Math.max(source.localPollution, source.emissionRate * PollutionConfig.POLLUTION_SOURCE_VISUAL_MULTIPLIER.get().floatValue());
    }

    private static float combinedTargetValue(ClientPollutionCell cell) {
        return Math.max(cell.serverTargetPollution, cell.localTargetPollution);
    }

    private static float combinedDisplayValue(ClientPollutionCell cell) {
        return Math.max(cell.serverDisplayPollution, cell.localDisplayPollution);
    }

    private static void queueRerenderWithMargin(int chunkX, int chunkZ) {
        int margin = PollutionConfig.effectiveRerenderMarginChunks();
        for (int dz = -margin; dz <= margin; dz++) {
            for (int dx = -margin; dx <= margin; dx++) {
                queueRerenderChunk(chunkX + dx, chunkZ + dz);
            }
        }
    }

    private static void queueRerenderChunk(int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        if (queuedPollutionRerenders.add(key)) {
            pendingPollutionRerenders.add(key);
        }
    }

    private static void publishRenderSnapshotIfDirty() {
        if (!renderSnapshotDirty) {
            return;
        }

        Map<Long, Float> serverGrassByChunk = new HashMap<>();
        Map<Long, Float> localGrassByChunk = new HashMap<>();
        Map<Long, Float> serverDisplayByChunk = new HashMap<>();
        Map<Long, Float> localDisplayByChunk = new HashMap<>();
        for (Map.Entry<Long, ClientPollutionCell> entry : clientPollutionByChunk.entrySet()) {
            ClientPollutionCell cell = entry.getValue();
            if (cell.serverTargetPollution > 0.0F) {
                serverGrassByChunk.put(entry.getKey(), cell.serverTargetPollution);
            }
            if (cell.localTargetPollution > 0.0F) {
                localGrassByChunk.put(entry.getKey(), cell.localTargetPollution);
            }
            if (cell.serverDisplayPollution > 0.0F) {
                serverDisplayByChunk.put(entry.getKey(), cell.serverDisplayPollution);
            }
            if (cell.localDisplayPollution > 0.0F) {
                localDisplayByChunk.put(entry.getKey(), cell.localDisplayPollution);
            }
        }

        List<VisualSourceSnapshot> sourceSnapshots = new ArrayList<>(knownVisualSources.size());
        for (ClientVisualSource source : knownVisualSources.values()) {
            if (source.localPollution <= 0.0F && source.emissionRate <= 0.0F) {
                continue;
            }
            sourceSnapshots.add(new VisualSourceSnapshot(
                    source.blockX,
                    source.blockY,
                    source.blockZ,
                    source.emissionRate,
                    source.localPollution,
                    source.sourceType
            ));
        }

        renderSnapshot = new PollutionRenderSnapshot(
                Map.copyOf(serverGrassByChunk),
                Map.copyOf(localGrassByChunk),
                Map.copyOf(serverDisplayByChunk),
                Map.copyOf(localDisplayByChunk),
                List.copyOf(sourceSnapshots),
                Math.max(1, PollutionConfig.effectiveGridEdgeFadeChunks()),
                (float) PollutionConfig.effectiveSourceVisualRadiusBlocks(),
                PollutionConfig.POLLUTION_SOURCE_VISUAL_MULTIPLIER.get().floatValue()
        );
        renderSnapshotDirty = false;
    }

    private static void updateFogActive() {
        if (pollutionFogActive) {
            if (currentFogPollution < PollutionConfig.POLLUTION_FOG_DISABLE_THRESHOLD.get()) {
                pollutionFogActive = false;
            }
        } else if (currentFogPollution > PollutionConfig.POLLUTION_FOG_ENABLE_THRESHOLD.get()) {
            pollutionFogActive = true;
        }
    }

    private static float approachLayer(float current, float target, float riseRate, float fallRate, float epsilon) {
        float cleanTarget = target < epsilon ? 0.0F : target;
        float cleanCurrent = current < epsilon ? 0.0F : current;
        return approach(cleanCurrent, cleanTarget, cleanTarget > cleanCurrent ? riseRate : fallRate, epsilon);
    }

    private static float approach(float current, float target, float rate, float epsilon) {
        float next = current + (target - current) * rate;
        if (Math.abs(next) < epsilon && target == 0.0F) {
            return 0.0F;
        }
        return Math.max(0.0F, next);
    }

    private static double smoothstep(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private static int gridSize(int radius) {
        return radius * 2 + 1;
    }

    private static float sanitize(float value) {
        return Float.isFinite(value) && value > 0.0F ? value : 0.0F;
    }

    private static void debugClientLayers(String message) {
        if (!DEBUG_CLIENT_LAYERS) {
            return;
        }
        Pollution.LOGGER.info(
                "Client pollution layers: {} serverSyncActive={} serverCells={} localCells={} sources={} cache={} rerenders={}",
                message,
                serverSyncActive,
                renderSnapshot.serverGrassByChunk.size(),
                renderSnapshot.localGrassByChunk.size(),
                knownVisualSources.size(),
                clientPollutionByChunk.size(),
                pendingPollutionRerenders.size()
        );
    }

    private static final class ClientPollutionCell {
        private final int chunkX;
        private final int chunkZ;
        private float serverTargetPollution;
        private float serverDisplayPollution;
        private float localTargetPollution;
        private float localDisplayPollution;
        private long lastServerSeenGameTime = -1L;
        private long lastLocalSeenGameTime = -1L;
        private long lastUpdatedGameTime;

        private ClientPollutionCell(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }

    private static final class ClientVisualSource {
        private final int blockX;
        private final int blockY;
        private final int blockZ;
        private float emissionRate;
        private float localPollution;
        private byte sourceType;
        private long lastSeenGameTime;
        private boolean serverConfirmed;

        private ClientVisualSource(int blockX, int blockY, int blockZ) {
            this.blockX = blockX;
            this.blockY = blockY;
            this.blockZ = blockZ;
        }
    }

    private static final class PollutionRenderSnapshot {
        private static final PollutionRenderSnapshot EMPTY = new PollutionRenderSnapshot(Map.of(), Map.of(), Map.of(), Map.of(), List.of(), 1, 48.0F, 25.0F);

        private final Map<Long, Float> serverGrassByChunk;
        private final Map<Long, Float> localGrassByChunk;
        private final Map<Long, Float> serverDisplayByChunk;
        private final Map<Long, Float> localDisplayByChunk;
        private final List<VisualSourceSnapshot> visualSources;
        private final int edgeFadeChunks;
        private final float sourceVisualRadiusBlocks;
        private final float sourceVisualMultiplier;

        private PollutionRenderSnapshot(
                Map<Long, Float> serverGrassByChunk,
                Map<Long, Float> localGrassByChunk,
                Map<Long, Float> serverDisplayByChunk,
                Map<Long, Float> localDisplayByChunk,
                List<VisualSourceSnapshot> visualSources,
                int edgeFadeChunks,
                float sourceVisualRadiusBlocks,
                float sourceVisualMultiplier
        ) {
            this.serverGrassByChunk = serverGrassByChunk;
            this.localGrassByChunk = localGrassByChunk;
            this.serverDisplayByChunk = serverDisplayByChunk;
            this.localDisplayByChunk = localDisplayByChunk;
            this.visualSources = visualSources;
            this.edgeFadeChunks = edgeFadeChunks;
            this.sourceVisualRadiusBlocks = sourceVisualRadiusBlocks;
            this.sourceVisualMultiplier = sourceVisualMultiplier;
        }

        private float sampleGrassPollutionAtBlock(BlockPos pos) {
            return max(
                    sampleCachedPollutionAtBlock(pos, serverGrassByChunk),
                    sampleCachedPollutionAtBlock(pos, localGrassByChunk),
                    sampleNearbyVisualSources(pos)
            );
        }

        private float sampleDisplayPollutionAtBlock(BlockPos pos) {
            return max(
                    sampleCachedPollutionAtBlock(pos, serverDisplayByChunk),
                    sampleCachedPollutionAtBlock(pos, localDisplayByChunk),
                    sampleNearbyVisualSources(pos)
            );
        }

        private float sampleCachedPollutionAtBlock(BlockPos pos, Map<Long, Float> valuesByChunk) {
            if (valuesByChunk.isEmpty() || pos == null) {
                return 0.0F;
            }

            int chunkX = Math.floorDiv(pos.getX(), 16);
            int chunkZ = Math.floorDiv(pos.getZ(), 16);
            float fx = Math.floorMod(pos.getX(), 16) / 16.0F;
            float fz = Math.floorMod(pos.getZ(), 16) / 16.0F;
            float v00 = cachedValueOrNeighbour(chunkX, chunkZ, valuesByChunk);
            float v10 = cachedValueOrNeighbour(chunkX + 1, chunkZ, valuesByChunk);
            float v01 = cachedValueOrNeighbour(chunkX, chunkZ + 1, valuesByChunk);
            float v11 = cachedValueOrNeighbour(chunkX + 1, chunkZ + 1, valuesByChunk);
            float north = Mth.lerp(fx, v00, v10);
            float south = Mth.lerp(fx, v01, v11);
            return Mth.lerp(fz, north, south) * cacheEdgeFade(chunkX, chunkZ, valuesByChunk);
        }

        private float cachedValueOrNeighbour(int chunkX, int chunkZ, Map<Long, Float> valuesByChunk) {
            Float value = valuesByChunk.get(ChunkPos.asLong(chunkX, chunkZ));
            if (value != null) {
                return value;
            }

            float total = 0.0F;
            float weight = 0.0F;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    Float neighbour = valuesByChunk.get(ChunkPos.asLong(chunkX + dx, chunkZ + dz));
                    if (neighbour == null) {
                        continue;
                    }
                    float neighbourWeight = dx == 0 || dz == 0 ? 1.0F : 0.6F;
                    total += neighbour * neighbourWeight;
                    weight += neighbourWeight;
                }
            }
            return weight > 0.0F ? total / weight : 0.0F;
        }

        private float cacheEdgeFade(int chunkX, int chunkZ, Map<Long, Float> valuesByChunk) {
            if (valuesByChunk.containsKey(ChunkPos.asLong(chunkX, chunkZ))) {
                return 1.0F;
            }

            for (int distance = 1; distance <= edgeFadeChunks; distance++) {
                if (hasCachedChunkOnRing(chunkX, chunkZ, distance, valuesByChunk)) {
                    double fade = 1.0 - Math.max(0.0, Math.min(1.0, distance / (double) edgeFadeChunks));
                    return (float) smoothstep(fade);
                }
            }
            return 0.0F;
        }

        private boolean hasCachedChunkOnRing(int chunkX, int chunkZ, int distance, Map<Long, Float> valuesByChunk) {
            for (int dz = -distance; dz <= distance; dz++) {
                if (valuesByChunk.containsKey(ChunkPos.asLong(chunkX - distance, chunkZ + dz))
                        || valuesByChunk.containsKey(ChunkPos.asLong(chunkX + distance, chunkZ + dz))) {
                    return true;
                }
            }
            for (int dx = -distance + 1; dx <= distance - 1; dx++) {
                if (valuesByChunk.containsKey(ChunkPos.asLong(chunkX + dx, chunkZ - distance))
                        || valuesByChunk.containsKey(ChunkPos.asLong(chunkX + dx, chunkZ + distance))) {
                    return true;
                }
            }
            return false;
        }

        private float sampleNearbyVisualSources(BlockPos pos) {
            if (pos == null || visualSources.isEmpty()) {
                return 0.0F;
            }

            double radiusSq = sourceVisualRadiusBlocks * sourceVisualRadiusBlocks;
            double blockX = pos.getX() + 0.5;
            double blockZ = pos.getZ() + 0.5;
            float best = 0.0F;
            for (VisualSourceSnapshot source : visualSources) {
                double dx = blockX - (source.blockX + 0.5);
                double dz = blockZ - (source.blockZ + 0.5);
                double distanceSq = dx * dx + dz * dz;
                if (distanceSq > radiusSq) {
                    continue;
                }
                double t = 1.0 - Math.sqrt(distanceSq) / sourceVisualRadiusBlocks;
                double sourcePollution = Math.max(source.localPollution, source.emissionRate * sourceVisualMultiplier);
                best = Math.max(best, (float) (sourcePollution * smoothstep(t)));
            }
            return best;
        }

        private float max(float a, float b, float c) {
            return Math.max(a, Math.max(b, c));
        }
    }

    private record VisualSourceSnapshot(int blockX, int blockY, int blockZ, float emissionRate, float localPollution, byte sourceType) {
    }
}
