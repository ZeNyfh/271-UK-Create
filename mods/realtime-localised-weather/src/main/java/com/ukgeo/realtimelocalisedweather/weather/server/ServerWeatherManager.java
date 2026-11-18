package com.ukgeo.realtimelocalisedweather.weather.server;

import com.ukgeo.realtimelocalisedweather.RealtimeLocalisedWeatherMod;
import com.ukgeo.realtimelocalisedweather.api.RegionalWeatherAccess;
import com.ukgeo.realtimelocalisedweather.cache.WeatherMemoryCache;
import com.ukgeo.realtimelocalisedweather.compat.sereneseasons.SerenePrecipitationResolver;
import com.ukgeo.realtimelocalisedweather.compat.sereneseasons.SereneSeasonSnapshot;
import com.ukgeo.realtimelocalisedweather.compat.sereneseasons.SereneSeasonsCompat;
import com.ukgeo.realtimelocalisedweather.config.ServerWeatherConfig;
import com.ukgeo.realtimelocalisedweather.network.TileSnapshotPayload;
import com.ukgeo.realtimelocalisedweather.network.UkGeoReferencePayload;
import com.ukgeo.realtimelocalisedweather.network.WeatherAuthorityModePayload;
import com.ukgeo.realtimelocalisedweather.network.WeatherInitialGridPayload;
import com.ukgeo.realtimelocalisedweather.network.WeatherLightningPayload;
import com.ukgeo.realtimelocalisedweather.network.WeatherPollRequestPayload;
import com.ukgeo.realtimelocalisedweather.network.WeatherProtocolPayload;
import com.ukgeo.realtimelocalisedweather.network.WeatherTileRemovePayload;
import com.ukgeo.realtimelocalisedweather.network.WeatherTileUpdatePayload;
import com.ukgeo.realtimelocalisedweather.openmeteo.NationalWeatherGrid;
import com.ukgeo.realtimelocalisedweather.openmeteo.OpenMeteoClient;
import com.ukgeo.realtimelocalisedweather.openmeteo.OpenMeteoResponse;
import com.ukgeo.realtimelocalisedweather.rainviewer.RainViewerClient;
import com.ukgeo.realtimelocalisedweather.rainviewer.RainViewerMath;
import com.ukgeo.realtimelocalisedweather.weather.MeteorologicalPrecipitation;
import com.ukgeo.realtimelocalisedweather.weather.GameplaySeverity;
import com.ukgeo.realtimelocalisedweather.weather.LocalWeatherState;
import com.ukgeo.realtimelocalisedweather.weather.ResolvedPrecipitation;
import com.ukgeo.realtimelocalisedweather.weather.ServerWeatherSnapshot;
import com.ukgeo.realtimelocalisedweather.weather.WeatherAuthorityMode;
import com.ukgeo.realtimelocalisedweather.weather.WeatherSeverityMapper;
import com.ukgeo.realtimelocalisedweather.weather.WeatherTileKey;
import com.ukgeo.worldgen.geo.UkGeoReference;
import com.ukgeo.worldgen.geo.UkGeoReferenceProvider;
import com.ukgeo.worldgen.geo.Wgs84Coordinate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ServerWeatherManager implements RegionalWeatherAccess {
    private static final Duration WEATHER_FETCH_FAILURE_INITIAL_BACKOFF = Duration.ofMinutes(2);
    private static final Duration WEATHER_FETCH_FAILURE_MAX_BACKOFF = Duration.ofMinutes(30);
    private static final Duration WEATHER_FETCH_LOG_INTERVAL = Duration.ofMinutes(1);
    /** Approx column samples per Minecraft day: 1200 gameplay ticks/day x 2 samples / 256 columns. */
    private static final double GAMEPLAY_COLUMN_SAMPLES_PER_MINECRAFT_DAY = 9.375D;

    private final java.util.concurrent.ExecutorService weatherHttpExecutor = Executors.newFixedThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "realtime-localised-weather-http");
        thread.setDaemon(true);
        return thread;
    });
    private final OpenMeteoClient openMeteoClient = new OpenMeteoClient(weatherHttpExecutor);
    private final RainViewerClient rainViewerClient = new RainViewerClient(weatherHttpExecutor);
    private final Map<net.minecraft.resources.ResourceKey<Level>, LevelState> levelStates = new HashMap<>();

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !ServerWeatherConfig.ENABLED.get()) {
            return;
        }
        Optional<UkGeoReference> reference = UkGeoReferenceProvider.get(level);
        if (reference.isEmpty()) {
            return;
        }
        LevelState state = levelStates.computeIfAbsent(level.dimension(), ignored -> new LevelState(reference.get()));
        state.reference = reference.get();
        state.overrideManager.clearExpired(System.currentTimeMillis());
        if (state.mode != WeatherAuthorityMode.VANILLA) {
            level.setWeatherParameters(0, 0, false, false);
        }
        long gameTime = level.getGameTime();
        if (gameTime % 40L == 0L) {
            updateActiveTiles(level, state);
            if (state.mode == WeatherAuthorityMode.LIVE) {
                requestFetches(level, state, false);
            }
        }
        if (state.mode == WeatherAuthorityMode.LIVE && gameTime % (20L * ServerWeatherConfig.REFRESH_INTERVAL_MINUTES.get()) == 0L) {
            requestFetches(level, state, false);
        }
        if (state.refreshRequested) {
            state.refreshRequested = false;
            requestFetches(level, state, true);
        }
        if (gameTime % 20L == 0L && state.mode != WeatherAuthorityMode.VANILLA) {
            tickGameplay(level, state);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendInitialSync(player);
        }
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            clearPlayerTracking(player.getUUID());
            sendInitialSync(player);
        }
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            clearPlayerTracking(player.getUUID());
            sendInitialSync(player);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        clearPlayerTracking(event.getEntity().getUUID());
    }

    public void requestRefresh(ServerLevel level) {
        LevelState state = levelStates.get(level.dimension());
        if (state != null) {
            state.refreshRequested = true;
        }
    }

    public void handleClientPoll(ServerPlayer player, WeatherPollRequestPayload payload) {
        ServerLevel level = player.serverLevel();
        Optional<UkGeoReference> reference = UkGeoReferenceProvider.get(level);
        if (reference.isEmpty()) {
            return;
        }
        LevelState state = levelStates.computeIfAbsent(level.dimension(), ignored -> new LevelState(reference.get()));
        state.reference = reference.get();
        WeatherTileKey playerTile = WeatherTileKey.fromBlock(level.dimension(), player.blockPosition().getX(), player.blockPosition().getZ(), ServerWeatherConfig.ZONE_SIZE_BLOCKS.get());
        trackActiveTiles(state, Set.of(playerTile), System.currentTimeMillis());
        if (state.mode == WeatherAuthorityMode.LIVE) {
            requestFetches(level, state, false, Set.of(playerTile), true);
        }
        updateActiveTiles(level, state);
        if (state.mode == WeatherAuthorityMode.LIVE) {
            requestFetches(level, state, false);
        }
        syncPlayer(level, state, player, false, false);
    }

    public void setMode(ServerLevel level, WeatherAuthorityMode mode) {
        requireLevelState(level).mode = mode;
        PacketDistributor.sendToAllPlayers(new WeatherAuthorityModePayload(level.dimension().location().toString(), mode));
    }

    public void clearOverride(ServerLevel level) {
        LevelState state = levelStates.get(level.dimension());
        if (state != null) {
            state.overrideManager.clear();
        }
    }

    public void applyGlobalOverride(ServerLevel level, ResolvedPrecipitation precipitation, GameplaySeverity severity, long durationMillis) {
        LevelState state = requireLevelState(level);
        state.mode = WeatherAuthorityMode.MANUAL;
        state.overrideManager.setGlobal(precipitation, severity, durationMillis);
        syncPlayers(level, state, true);
    }

    public void applyRegionalOverride(ServerLevel level, int tileX, int tileZ, ResolvedPrecipitation precipitation, GameplaySeverity severity, long durationMillis) {
        LevelState state = requireLevelState(level);
        state.mode = WeatherAuthorityMode.MANUAL;
        state.overrideManager.setTile(new WeatherTileKey(level.dimension(), tileX, tileZ), precipitation, severity, durationMillis);
        syncPlayers(level, state, true);
    }

    public void applyVisualRainOverride(ServerLevel level, BlockPos position, int percent) {
        applyVisualPrecipitationOverride(level, position, percent, false);
    }

    public void applyVisualSnowOverride(ServerLevel level, BlockPos position, int percent) {
        applyVisualPrecipitationOverride(level, position, percent, true);
    }

    private void applyVisualPrecipitationOverride(ServerLevel level, BlockPos position, int percent, boolean snow) {
        // /rain and /snow are visual debug tools and must work even when UKGeo location data is unavailable.
        LevelState state = requireLevelState(level, true);
        int zoneSize = ServerWeatherConfig.ZONE_SIZE_BLOCKS.get();
        WeatherTileKey key = WeatherTileKey.fromBlock(level.dimension(), position.getX(), position.getZ(), zoneSize);
        long now = System.currentTimeMillis();
        // Ensure nearby tiles exist with a base snapshot so the override can sync even before
        // Open-Meteo has returned live weather for this area.
        Set<WeatherTileKey> relevant = ActiveTileTracker.collect(
            level.dimension(),
            List.of(position),
            zoneSize,
            ServerWeatherConfig.ACTIVE_ZONE_RADIUS.get(),
            ServerWeatherConfig.PREFETCH_ZONE_RADIUS.get()
        );
        trackActiveTiles(state, relevant, now);
        for (WeatherTileKey tileKey : relevant) {
            ManagedTile tile = state.tiles.get(tileKey);
            if (tile != null && tile.snapshot == null) {
                tile.snapshot = placeholderSnapshot();
            }
        }
        if (snow) {
            state.visualOverrideManager.setSnow(key, percent);
        } else {
            state.visualOverrideManager.setRain(key, percent);
        }
        syncPlayers(level, state, true);
    }

    private LevelState requireLevelState(ServerLevel level) {
        return requireLevelState(level, false);
    }

    private LevelState requireLevelState(ServerLevel level, boolean allowMissingUkGeoReference) {
        LevelState existing = levelStates.get(level.dimension());
        Optional<UkGeoReference> reference = UkGeoReferenceProvider.get(level);
        if (existing != null) {
            reference.ifPresent(value -> existing.reference = value);
            return existing;
        }
        if (reference.isEmpty() && !allowMissingUkGeoReference) {
            throw new IllegalStateException(
                "Realtime Localised Weather is not ready yet (UKGeo reference unavailable). Wait for the world to finish loading, then try again."
            );
        }
        return levelStates.computeIfAbsent(level.dimension(), ignored -> new LevelState(reference.orElse(null)));
    }

    private static ServerWeatherSnapshot placeholderSnapshot() {
        return new ServerWeatherSnapshot(
            Instant.now(),
            0.0D,
            0.0D,
            0,
            MeteorologicalPrecipitation.NONE,
            0.0F,
            0.0F,
            0.0F,
            15.0F,
            70.0F,
            10000.0F,
            0.0F,
            0.0F,
            0.0F,
            ResolvedPrecipitation.NONE,
            GameplaySeverity.TRACE,
            0.0F,
            false,
            0L
        );
    }

    public String sampleStatus(ServerLevel level, int x, int z) {
        LocalWeatherState weather = getWeatherAt(level, new BlockPos(x, level.getSeaLevel(), z));
        ServerWeatherSnapshot snapshot = weather.snapshot();
        if (snapshot == null) {
            return "No realtime weather snapshot for x=" + x + " z=" + z;
        }
        return "tile=%d,%d resolved=%s severity=%s rate=%.2fmm/h temp=%.1fC stale=%s".formatted(
            weather.key().tileX(),
            weather.key().tileZ(),
            snapshot.resolvedPrecipitation(),
            snapshot.gameplaySeverity(),
            snapshot.precipitationRateMmPerHour(),
            snapshot.temperatureCelsius(),
            snapshot.stale()
        );
    }


    public String precipitationStatus(ServerLevel level, int x, int z) {
        LocalWeatherState weather = ensureWeatherRequestedAt(level, new BlockPos(x, level.getSeaLevel(), z));
        ServerWeatherSnapshot snapshot = weather.snapshot();
        if (snapshot == null) {
            return "Precipitation unavailable at x=%d z=%d tile=%d,%d; realtime weather snapshot requested but not loaded yet.".formatted(
                x,
                z,
                weather.key().tileX(),
                weather.key().tileZ()
            );
        }
        return "Precipitation at x=%d z=%d: %.2fmm/h total (rain %.2fmm/h, snow %.2fcm/h) resolved=%s severity=%s tile=%d,%d stale=%s".formatted(
            x,
            z,
            snapshot.precipitationRateMmPerHour(),
            snapshot.rainRateMmPerHour(),
            snapshot.snowfallRateCmPerHour(),
            snapshot.resolvedPrecipitation(),
            snapshot.gameplaySeverity(),
            weather.key().tileX(),
            weather.key().tileZ(),
            snapshot.stale()
        );
    }

    private LocalWeatherState ensureWeatherRequestedAt(ServerLevel level, BlockPos position) {
        Optional<UkGeoReference> reference = UkGeoReferenceProvider.get(level);
        WeatherTileKey key = WeatherTileKey.fromBlock(level.dimension(), position.getX(), position.getZ(), ServerWeatherConfig.ZONE_SIZE_BLOCKS.get());
        if (reference.isEmpty()) {
            return new LocalWeatherState(key, null, false);
        }
        LevelState state = levelStates.computeIfAbsent(level.dimension(), ignored -> new LevelState(reference.get()));
        state.reference = reference.get();
        Set<WeatherTileKey> relevant = Set.of(key);
        Set<WeatherTileKey> merged = new HashSet<>(state.activeTiles);
        merged.addAll(relevant);
        state.activeTiles = Set.copyOf(merged);
        trackActiveTiles(state, relevant, System.currentTimeMillis());
        if (state.mode == WeatherAuthorityMode.LIVE) {
            requestFetches(level, state, false, relevant, true);
        }
        return getWeatherAt(level, position);
    }

    public String status(ServerLevel level) {
        LevelState state = levelStates.get(level.dimension());
        if (state == null) {
            return "Realtime Localised Weather inactive for " + level.dimension().location();
        }
        return "mode=%s lastSuccess=%s activeTiles=%d cachedTiles=%d pendingRequests=%d staleTiles=%d nextFetchAllowed=%s backoffSeconds=%d protocol=%s sereneSeasons=%s".formatted(
            state.mode,
            state.lastSuccessfulRefresh,
            state.activeTiles.size(),
            state.tiles.size(),
            state.pendingFetches.size(),
            state.staleTileCount(),
            state.nextFetchAllowed,
            state.fetchFailureBackoff.toSeconds(),
            RealtimeLocalisedWeatherMod.PROTOCOL_VERSION,
            SereneSeasonsCompat.isLoaded()
        );
    }

    @Override
    public LocalWeatherState getWeatherAt(ServerLevel level, BlockPos position) {
        LevelState state = levelStates.get(level.dimension());
        if (state == null) {
            return new LocalWeatherState(WeatherTileKey.fromBlock(level.dimension(), position.getX(), position.getZ(), ServerWeatherConfig.ZONE_SIZE_BLOCKS.get()), null, false);
        }
        WeatherTileKey key = WeatherTileKey.fromBlock(level.dimension(), position.getX(), position.getZ(), ServerWeatherConfig.ZONE_SIZE_BLOCKS.get());
        ServerWeatherSnapshot snapshot = resolvedSnapshotFor(level, state, key, position);
        return new LocalWeatherState(key, snapshot, snapshot != null && snapshot.resolvedPrecipitation() != state.baseSnapshotFor(key).map(ServerWeatherSnapshot::resolvedPrecipitation).orElse(snapshot.resolvedPrecipitation()));
    }

    @Override
    public boolean isPrecipitatingAt(ServerLevel level, BlockPos position) {
        LocalWeatherState state = getWeatherAt(level, position);
        return state.snapshot() != null && state.snapshot().resolvedPrecipitation().isPrecipitating();
    }

    @Override
    public boolean isRainingAt(ServerLevel level, BlockPos position) {
        LocalWeatherState state = getWeatherAt(level, position);
        return state.snapshot() != null && state.snapshot().resolvedPrecipitation().isLiquid();
    }

    @Override
    public boolean isSnowingAt(ServerLevel level, BlockPos position) {
        LocalWeatherState state = getWeatherAt(level, position);
        return state.snapshot() != null && state.snapshot().resolvedPrecipitation().isSnowy();
    }

    @Override
    public boolean isThunderingAt(ServerLevel level, BlockPos position) {
        LocalWeatherState state = getWeatherAt(level, position);
        return state.snapshot() != null && state.snapshot().resolvedPrecipitation().supportsThunder();
    }

    public boolean isGlobalRaining(ServerLevel level) {
        LevelState state = levelStates.get(level.dimension());
        return state != null && state.tiles.values().stream().map(tile -> tile.snapshot).anyMatch(snapshot -> snapshot != null && snapshot.resolvedPrecipitation().isPrecipitating());
    }

    public boolean isGlobalThundering(ServerLevel level) {
        LevelState state = levelStates.get(level.dimension());
        return state != null && state.tiles.values().stream().map(tile -> tile.snapshot).anyMatch(snapshot -> snapshot != null && snapshot.resolvedPrecipitation().supportsThunder());
    }

    public boolean usesRegionalWeather(ServerLevel level) {
        LevelState state = levelStates.get(level.dimension());
        return state != null && state.mode != WeatherAuthorityMode.VANILLA;
    }

    private void updateActiveTiles(ServerLevel level, LevelState state) {
        List<BlockPos> positions = level.players().stream().map(ServerPlayer::blockPosition).toList();
        state.activeTiles = ActiveTileTracker.collect(level.dimension(), positions, ServerWeatherConfig.ZONE_SIZE_BLOCKS.get(), ServerWeatherConfig.ACTIVE_ZONE_RADIUS.get(), ServerWeatherConfig.PREFETCH_ZONE_RADIUS.get());
        long now = System.currentTimeMillis();
        trackActiveTiles(state, state.activeTiles, now);
        long retention = Duration.ofMinutes(ServerWeatherConfig.INACTIVE_TILE_RETENTION_MINUTES.get()).toMillis();
        state.tiles.entrySet().removeIf(entry -> !state.activeTiles.contains(entry.getKey()) && now - entry.getValue().lastTouchedMillis > retention);
    }

    private void trackActiveTiles(LevelState state, Set<WeatherTileKey> activeTiles, long now) {
        for (WeatherTileKey activeTile : activeTiles) {
            ManagedTile tile = state.tiles.get(activeTile);
            if (tile == null) {
                Optional<WeatherMemoryCache.Entry> cached = state.memoryCache.get(activeTile);
                tile = cached
                    .map(entry -> new ManagedTile(entry.snapshot(), entry.snapshot().revision(), now, randomSeed(activeTile)))
                    .orElseGet(() -> new ManagedTile(null, 0L, now, randomSeed(activeTile)));
                state.tiles.put(activeTile, tile);
            } else if (tile.snapshot == null) {
                Optional<WeatherMemoryCache.Entry> cached = state.memoryCache.get(activeTile);
                if (cached.isPresent()) {
                    WeatherMemoryCache.Entry entry = cached.get();
                    tile.snapshot = entry.snapshot();
                    tile.revision = Math.max(tile.revision, entry.snapshot().revision());
                }
            }
            if (tile != null) {
                tile.lastTouchedMillis = now;
            }
        }
    }

    private void requestFetches(ServerLevel level, LevelState state, boolean force) {
        requestFetches(level, state, force, state.activeTiles, false);
    }

    private void requestFetches(ServerLevel level, LevelState state, boolean force, Collection<WeatherTileKey> candidateTiles, boolean priorityMissingTile) {
        Instant now = Instant.now();
        if (!force && now.isBefore(state.nextFetchAllowed)) {
            if (!priorityMissingTile || !state.fetchFailureBackoff.isZero()) {
                return;
            }
        }
        if (state.reference == null) {
            return;
        }
        Duration staleAfter = Duration.ofHours(ServerWeatherConfig.STALE_CACHE_HOURS.get());
        List<StaleCandidate> staleCandidates = new ArrayList<>();
        for (WeatherTileKey key : candidateTiles) {
            ManagedTile tile = state.tiles.get(key);
            boolean neverSucceeded = tile == null || tile.snapshot == null;
            boolean stale = neverSucceeded || tile.snapshot.observedAt().plus(staleAfter).isBefore(now);
            if ((force || stale) && !state.pendingFetches.containsKey(key)) {
                Instant observed = neverSucceeded ? Instant.EPOCH : tile.snapshot.observedAt();
                staleCandidates.add(new StaleCandidate(key, observed, neverSucceeded));
            }
        }
        if (staleCandidates.isEmpty()) {
            return;
        }
        // Prefer refreshing already-known tiles that went stale; stagger cold-start tiles.
        staleCandidates.sort((left, right) -> {
            if (left.neverSucceeded() != right.neverSucceeded()) {
                return left.neverSucceeded() ? 1 : -1;
            }
            int byAge = left.observedAt().compareTo(right.observedAt());
            if (byAge != 0) {
                return byAge;
            }
            int byX = Integer.compare(left.key().tileX(), right.key().tileX());
            return byX != 0 ? byX : Integer.compare(left.key().tileZ(), right.key().tileZ());
        });

        if (ServerWeatherConfig.safeBoolean(ServerWeatherConfig.RAINVIEWER_ENABLED, true)) {
            requestFetchesRainViewer(level, state, force, priorityMissingTile, staleCandidates, now);
            return;
        }
        if (ServerWeatherConfig.safeBoolean(ServerWeatherConfig.NATIONAL_GRID_ENABLED, true)) {
            requestFetchesNational(level, state, force, priorityMissingTile, staleCandidates, now);
            return;
        }
        requestFetchesPerTile(level, state, force, priorityMissingTile, staleCandidates, now);
    }

    private void requestFetchesRainViewer(
        ServerLevel level,
        LevelState state,
        boolean force,
        boolean priorityMissingTile,
        List<StaleCandidate> staleCandidates,
        Instant now
    ) {
        List<WeatherTileKey> staleKeys = staleCandidates.stream().map(StaleCandidate::key).toList();
        Duration frameFreshFor = Duration.ofMinutes(Math.max(1, ServerWeatherConfig.safeInt(ServerWeatherConfig.REFRESH_INTERVAL_MINUTES, 15)));
        boolean frameFresh = !force && rainViewerClient.hasFrame()
            && !now.isAfter(rainViewerClient.observedAt().plus(frameFreshFor))
            && rainViewerClient.cachedTileCount() > 0;
        if (frameFresh) {
            int applied = applyRainViewerSamples(level, state, staleKeys);
            if (applied > 0) {
                state.nextFetchAllowed = now.plusSeconds(30);
                RealtimeLocalisedWeatherMod.LOGGER.info(
                    "Realtime weather sampled {} tile(s) from RainViewer radar (observedAt={}, tiles={})",
                    applied,
                    rainViewerClient.observedAt(),
                    rainViewerClient.cachedTileCount()
                );
                syncPlayers(level, state, false);
            }
            return;
        }
        if (state.pendingRainViewerRefresh != null && !state.pendingRainViewerRefresh.isDone()) {
            return;
        }
        RainViewerMath.BBox bbox = rainViewerBBoxFor(state, staleKeys);
        int maxTiles = ServerWeatherConfig.safeInt(ServerWeatherConfig.RAINVIEWER_MAX_TILES, 24);
        state.nextFetchAllowed = now.plusSeconds(30);
        RealtimeLocalisedWeatherMod.LOGGER.info(
            "Realtime weather refreshing RainViewer radar for {} stale tile(s) (maxTiles={} priority={} force={})",
            staleKeys.size(),
            maxTiles,
            priorityMissingTile,
            force
        );
        CompletableFuture<Void> future = rainViewerClient.ensureTiles(bbox, maxTiles);
        state.pendingRainViewerRefresh = future;
        CompletableFuture<List<OpenMeteoResponse.LocationWeather>> pendingMarker = future.thenApply(ignored -> List.<OpenMeteoResponse.LocationWeather>of());
        for (WeatherTileKey key : staleKeys) {
            state.pendingFetches.put(key, pendingMarker);
        }
        future.whenComplete((ignored, throwable) -> level.getServer().execute(() -> {
            state.pendingRainViewerRefresh = null;
            for (WeatherTileKey key : staleKeys) {
                state.pendingFetches.remove(key);
            }
            if (throwable != null) {
                Instant failureTime = Instant.now();
                String message = throwable.getMessage() == null ? "" : throwable.getMessage();
                Duration backoff = state.fetchFailureBackoff.isZero() ? WEATHER_FETCH_FAILURE_INITIAL_BACKOFF : state.fetchFailureBackoff.multipliedBy(2);
                if (backoff.compareTo(WEATHER_FETCH_FAILURE_MAX_BACKOFF) > 0) {
                    backoff = WEATHER_FETCH_FAILURE_MAX_BACKOFF;
                }
                state.fetchFailureBackoff = backoff;
                state.nextFetchAllowed = failureTime.plus(backoff);
                if (!failureTime.isBefore(state.nextFetchFailureLogAllowed)) {
                    RealtimeLocalisedWeatherMod.LOGGER.warn(
                        "Realtime weather RainViewer refresh failed: {}; falling back to Open-Meteo national; backing off for {}s",
                        message,
                        backoff.toSeconds()
                    );
                    state.nextFetchFailureLogAllowed = failureTime.plus(WEATHER_FETCH_LOG_INTERVAL);
                }
                for (WeatherTileKey key : staleKeys) {
                    ManagedTile existing = state.tiles.get(key);
                    if (existing != null && existing.snapshot != null) {
                        existing.snapshot = stale(existing.snapshot);
                    }
                }
                state.nextFetchAllowed = Instant.EPOCH;
                requestFetchesNational(level, state, force, priorityMissingTile, staleCandidates, Instant.now());
                return;
            }
            state.fetchFailureBackoff = Duration.ZERO;
            state.nextFetchAllowed = Instant.now().plusSeconds(30);
            int applied = applyRainViewerSamples(level, state, staleKeys);
            RealtimeLocalisedWeatherMod.LOGGER.info(
                "Realtime weather RainViewer ready; sampled {}/{} tile(s) (cachedTiles={})",
                applied,
                staleKeys.size(),
                rainViewerClient.cachedTileCount()
            );
            state.lastSuccessfulRefresh = Instant.now();
            syncPlayers(level, state, false);
        }));
    }

    private RainViewerMath.BBox rainViewerBBoxFor(LevelState state, Collection<WeatherTileKey> keys) {
        int tileSize = ServerWeatherConfig.ZONE_SIZE_BLOCKS.get();
        double minLat = Double.POSITIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        double minLon = Double.POSITIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;
        for (WeatherTileKey key : keys) {
            double centerX = key.tileX() * tileSize + tileSize / 2.0D;
            double centerZ = key.tileZ() * tileSize + tileSize / 2.0D;
            Optional<Wgs84Coordinate> wgs84 = state.reference.minecraftToWgs84(centerX, centerZ);
            if (wgs84.isEmpty()) {
                continue;
            }
            double lat = wgs84.get().latitude();
            double lon = wgs84.get().longitude();
            minLat = Math.min(minLat, lat);
            maxLat = Math.max(maxLat, lat);
            minLon = Math.min(minLon, lon);
            maxLon = Math.max(maxLon, lon);
        }
        if (!Double.isFinite(minLat) || !Double.isFinite(maxLat) || !Double.isFinite(minLon) || !Double.isFinite(maxLon)) {
            // Active tiles empty/out of range: cover UKGeo reference corners.
            for (double[] mz : new double[][] {
                {state.reference.minecraftMinX(), state.reference.minecraftMinZ()},
                {state.reference.minecraftMaxX(), state.reference.minecraftMinZ()},
                {state.reference.minecraftMinX(), state.reference.minecraftMaxZ()},
                {state.reference.minecraftMaxX(), state.reference.minecraftMaxZ()}
            }) {
                Optional<Wgs84Coordinate> wgs84 = state.reference.minecraftToWgs84(mz[0], mz[1]);
                if (wgs84.isEmpty()) {
                    continue;
                }
                minLat = Math.min(minLat, wgs84.get().latitude());
                maxLat = Math.max(maxLat, wgs84.get().latitude());
                minLon = Math.min(minLon, wgs84.get().longitude());
                maxLon = Math.max(maxLon, wgs84.get().longitude());
            }
        }
        if (!Double.isFinite(minLat) || !Double.isFinite(maxLat) || !Double.isFinite(minLon) || !Double.isFinite(maxLon)) {
            return new RainViewerMath.BBox(49.5D, 61.0D, -8.5D, 2.0D);
        }
        // Pad a little so edge tile centres still land inside fetched XYZ tiles.
        double padLat = Math.max(0.15D, (maxLat - minLat) * 0.05D);
        double padLon = Math.max(0.15D, (maxLon - minLon) * 0.05D);
        return new RainViewerMath.BBox(minLat - padLat, maxLat + padLat, minLon - padLon, maxLon + padLon);
    }

    private int applyRainViewerSamples(ServerLevel level, LevelState state, Collection<WeatherTileKey> keys) {
        if (!rainViewerClient.hasFrame()) {
            return 0;
        }
        int tileSize = ServerWeatherConfig.ZONE_SIZE_BLOCKS.get();
        int applied = 0;
        for (WeatherTileKey key : keys) {
            double centerX = key.tileX() * tileSize + tileSize / 2.0D;
            double centerZ = key.tileZ() * tileSize + tileSize / 2.0D;
            Optional<Wgs84Coordinate> wgs84 = state.reference.minecraftToWgs84(centerX, centerZ);
            if (wgs84.isEmpty()) {
                continue;
            }
            Optional<OpenMeteoResponse.LocationWeather> sampled = rainViewerClient.sampleLocation(
                wgs84.get().latitude(),
                wgs84.get().longitude()
            );
            if (sampled.isEmpty()) {
                continue;
            }
            ManagedTile tile = state.tiles.computeIfAbsent(key, ignored -> new ManagedTile(null, 0L, System.currentTimeMillis(), randomSeed(key)));
            tile.snapshot = buildSnapshot(level, state, key, sampled.get(), tile.revision + 1L);
            tile.revision = tile.snapshot.revision();
            tile.lastTouchedMillis = System.currentTimeMillis();
            state.memoryCache.put(key, tile.snapshot, Instant.now());
            applied++;
        }
        return applied;
    }

    private void requestFetchesNational(
        ServerLevel level,
        LevelState state,
        boolean force,
        boolean priorityMissingTile,
        List<StaleCandidate> staleCandidates,
        Instant now
    ) {
        List<WeatherTileKey> staleKeys = staleCandidates.stream().map(StaleCandidate::key).toList();
        int columns = ServerWeatherConfig.safeInt(ServerWeatherConfig.NATIONAL_GRID_COLUMNS, 8);
        int rows = ServerWeatherConfig.safeInt(ServerWeatherConfig.NATIONAL_GRID_ROWS, 10);
        if (state.nationalGrid == null || !state.nationalGrid.matchesDimensions(columns, rows)) {
            state.nationalGrid = NationalWeatherGrid.create(state.reference, columns, rows);
        }
        Duration gridFreshFor = Duration.ofMinutes(Math.max(1, ServerWeatherConfig.safeInt(ServerWeatherConfig.REFRESH_INTERVAL_MINUTES, 15)));
        boolean gridFresh = !force && state.nationalGrid.isFresh(gridFreshFor);
        if (gridFresh) {
            int applied = applyNationalGridSamples(level, state, staleKeys);
            if (applied > 0) {
                state.nextFetchAllowed = now.plusSeconds(30);
                RealtimeLocalisedWeatherMod.LOGGER.info(
                    "Realtime weather sampled {} tile(s) from national grid ({}x{}, observedAt={})",
                    applied,
                    state.nationalGrid.columns(),
                    state.nationalGrid.rows(),
                    state.nationalGrid.observedAt()
                );
                syncPlayers(level, state, false);
            }
            return;
        }
        if (state.pendingNationalRefresh != null && !state.pendingNationalRefresh.isDone()) {
            return;
        }
        state.nextFetchAllowed = now.plusSeconds(30);
        RealtimeLocalisedWeatherMod.LOGGER.info(
            "Realtime weather refreshing national grid {}x{} ({} point(s)) for {} stale tile(s) priority={} force={}",
            state.nationalGrid.columns(),
            state.nationalGrid.rows(),
            state.nationalGrid.pointCount(),
            staleKeys.size(),
            priorityMissingTile,
            force
        );
        CompletableFuture<Boolean> future = state.nationalGrid.refresh(openMeteoClient);
        state.pendingNationalRefresh = future;
        CompletableFuture<List<OpenMeteoResponse.LocationWeather>> pendingMarker = future.thenApply(ignored -> List.<OpenMeteoResponse.LocationWeather>of());
        for (WeatherTileKey key : staleKeys) {
            state.pendingFetches.put(key, pendingMarker);
        }
        future.whenComplete((ok, throwable) -> level.getServer().execute(() -> {
            state.pendingNationalRefresh = null;
            for (WeatherTileKey key : staleKeys) {
                state.pendingFetches.remove(key);
            }
            if (throwable != null || !Boolean.TRUE.equals(ok)) {
                Instant failureTime = Instant.now();
                String message = throwable == null
                    ? "national grid refresh returned false"
                    : (throwable.getMessage() == null ? "" : throwable.getMessage());
                boolean rateLimited = message.contains("429");
                Duration backoff = state.fetchFailureBackoff.isZero() ? WEATHER_FETCH_FAILURE_INITIAL_BACKOFF : state.fetchFailureBackoff.multipliedBy(2);
                if (rateLimited && backoff.compareTo(Duration.ofSeconds(120)) < 0) {
                    backoff = Duration.ofSeconds(120);
                }
                if (backoff.compareTo(WEATHER_FETCH_FAILURE_MAX_BACKOFF) > 0) {
                    backoff = WEATHER_FETCH_FAILURE_MAX_BACKOFF;
                }
                state.fetchFailureBackoff = backoff;
                state.nextFetchAllowed = failureTime.plus(backoff);
                if (!failureTime.isBefore(state.nextFetchFailureLogAllowed)) {
                    RealtimeLocalisedWeatherMod.LOGGER.warn(
                        "Realtime weather national grid refresh failed: {}; falling back to per-tile; backing off for {}s",
                        message,
                        backoff.toSeconds()
                    );
                    state.nextFetchFailureLogAllowed = failureTime.plus(WEATHER_FETCH_LOG_INTERVAL);
                }
                for (WeatherTileKey key : staleKeys) {
                    ManagedTile existing = state.tiles.get(key);
                    if (existing != null && existing.snapshot != null) {
                        existing.snapshot = stale(existing.snapshot);
                    }
                }
                if (!rateLimited) {
                    state.nextFetchAllowed = Instant.EPOCH;
                    requestFetchesPerTile(level, state, force, priorityMissingTile, staleCandidates, Instant.now());
                }
                return;
            }
            state.fetchFailureBackoff = Duration.ZERO;
            state.nextFetchAllowed = Instant.now().plusSeconds(30);
            int applied = applyNationalGridSamples(level, state, staleKeys);
            RealtimeLocalisedWeatherMod.LOGGER.info(
                "Realtime weather national grid ready; sampled {}/{} tile(s)",
                applied,
                staleKeys.size()
            );
            state.lastSuccessfulRefresh = Instant.now();
            syncPlayers(level, state, false);
        }));
    }

    private int applyNationalGridSamples(ServerLevel level, LevelState state, Collection<WeatherTileKey> keys) {
        if (state.nationalGrid == null || !state.nationalGrid.hasData()) {
            return 0;
        }
        int tileSize = ServerWeatherConfig.ZONE_SIZE_BLOCKS.get();
        int applied = 0;
        for (WeatherTileKey key : keys) {
            double centerX = key.tileX() * tileSize + tileSize / 2.0D;
            double centerZ = key.tileZ() * tileSize + tileSize / 2.0D;
            Optional<Wgs84Coordinate> wgs84 = state.reference.minecraftToWgs84(centerX, centerZ);
            if (wgs84.isEmpty()) {
                continue;
            }
            Optional<OpenMeteoResponse.LocationWeather> sampled = state.nationalGrid.sample(wgs84.get().latitude(), wgs84.get().longitude());
            if (sampled.isEmpty()) {
                continue;
            }
            ManagedTile tile = state.tiles.computeIfAbsent(key, ignored -> new ManagedTile(null, 0L, System.currentTimeMillis(), randomSeed(key)));
            tile.snapshot = buildSnapshot(level, state, key, sampled.get(), tile.revision + 1L);
            tile.revision = tile.snapshot.revision();
            tile.lastTouchedMillis = System.currentTimeMillis();
            state.memoryCache.put(key, tile.snapshot, Instant.now());
            applied++;
        }
        return applied;
    }

    private void requestFetchesPerTile(
        ServerLevel level,
        LevelState state,
        boolean force,
        boolean priorityMissingTile,
        List<StaleCandidate> staleCandidates,
        Instant now
    ) {
        int maxTiles = Math.max(1, ServerWeatherConfig.safeInt(ServerWeatherConfig.MAX_TILES_PER_REFRESH, 12));
        List<StaleCandidate> withData = staleCandidates.stream().filter(c -> !c.neverSucceeded()).toList();
        List<StaleCandidate> neverSucceeded = staleCandidates.stream().filter(StaleCandidate::neverSucceeded).toList();
        List<StaleCandidate> selected = new ArrayList<>();
        for (StaleCandidate candidate : withData) {
            if (selected.size() >= maxTiles) {
                break;
            }
            selected.add(candidate);
        }
        if (selected.size() < maxTiles && !neverSucceeded.isEmpty()) {
            // Rotate which never-fetched tiles are eligible each 30s window so all tiles are not requested together.
            int remainingCapacity = maxTiles - selected.size();
            int slices = Math.max(1, (neverSucceeded.size() + remainingCapacity - 1) / remainingCapacity);
            int slice = force ? 0 : (int) Math.floorMod(now.getEpochSecond() / 30L, slices);
            for (int i = 0; i < neverSucceeded.size(); i++) {
                if (selected.size() >= maxTiles) {
                    break;
                }
                if (force || Math.floorMod(i, slices) == slice) {
                    selected.add(neverSucceeded.get(i));
                }
            }
        }
        List<WeatherTileKey> required = new ArrayList<>();
        List<OpenMeteoResponse.LocationRequest> requests = new ArrayList<>();
        int unmapped = 0;
        for (StaleCandidate candidate : selected) {
            WeatherTileKey key = candidate.key();
            int tileSize = ServerWeatherConfig.ZONE_SIZE_BLOCKS.get();
            double centerX = key.tileX() * tileSize + tileSize / 2.0D;
            double centerZ = key.tileZ() * tileSize + tileSize / 2.0D;
            Optional<Wgs84Coordinate> wgs84 = state.reference.minecraftToWgs84(centerX, centerZ);
            if (wgs84.isPresent()) {
                required.add(key);
                requests.add(new OpenMeteoResponse.LocationRequest(key.tileX() + ":" + key.tileZ(), wgs84.get().latitude(), wgs84.get().longitude()));
            } else {
                unmapped++;
            }
        }
        if (requests.isEmpty()) {
            if (unmapped > 0) {
                RealtimeLocalisedWeatherMod.LOGGER.warn("Realtime weather skipped {} tile(s) outside UKGeo reference bounds", unmapped);
            }
            return;
        }
        state.nextFetchAllowed = now.plusSeconds(30);
        RealtimeLocalisedWeatherMod.LOGGER.info(
            "Realtime weather fetching {}/{} stale tile(s) priority={} force={}",
            requests.size(),
            staleCandidates.size(),
            priorityMissingTile,
            force
        );
        CompletableFuture<List<OpenMeteoResponse.LocationWeather>> future = openMeteoClient.fetchCurrent(requests);
        for (WeatherTileKey key : required) {
            state.pendingFetches.put(key, future);
        }
        future.whenComplete((locations, throwable) -> level.getServer().execute(() -> {
            if (throwable != null) {
                Instant failureTime = Instant.now();
                String message = throwable.getMessage() == null ? "" : throwable.getMessage();
                boolean rateLimited = message.contains("429");
                Duration backoff = state.fetchFailureBackoff.isZero() ? WEATHER_FETCH_FAILURE_INITIAL_BACKOFF : state.fetchFailureBackoff.multipliedBy(2);
                if (rateLimited && backoff.compareTo(Duration.ofSeconds(120)) < 0) {
                    backoff = Duration.ofSeconds(120);
                }
                if (backoff.compareTo(WEATHER_FETCH_FAILURE_MAX_BACKOFF) > 0) {
                    backoff = WEATHER_FETCH_FAILURE_MAX_BACKOFF;
                }
                state.fetchFailureBackoff = backoff;
                state.nextFetchAllowed = failureTime.plus(backoff);
                if (!failureTime.isBefore(state.nextFetchFailureLogAllowed)) {
                    RealtimeLocalisedWeatherMod.LOGGER.warn("Realtime weather refresh failed: {}; backing off for {}s", message, backoff.toSeconds());
                    state.nextFetchFailureLogAllowed = failureTime.plus(WEATHER_FETCH_LOG_INTERVAL);
                }
                for (WeatherTileKey key : required) {
                    state.pendingFetches.remove(key);
                    ManagedTile existing = state.tiles.get(key);
                    if (existing != null && existing.snapshot != null) {
                        existing.snapshot = stale(existing.snapshot);
                    }
                }
                return;
            }
            state.fetchFailureBackoff = Duration.ZERO;
            state.nextFetchAllowed = Instant.now().plusSeconds(30);
            int completed = Math.min(required.size(), locations.size());
            for (int i = 0; i < completed; i++) {
                WeatherTileKey key = required.get(i);
                ManagedTile tile = state.tiles.computeIfAbsent(key, ignored -> new ManagedTile(null, 0L, System.currentTimeMillis(), randomSeed(key)));
                tile.snapshot = buildSnapshot(level, state, key, locations.get(i), tile.revision + 1L);
                tile.revision = tile.snapshot.revision();
                tile.lastTouchedMillis = System.currentTimeMillis();
                state.memoryCache.put(key, tile.snapshot, Instant.now());
                state.pendingFetches.remove(key);
            }
            for (int i = completed; i < required.size(); i++) {
                state.pendingFetches.remove(required.get(i));
            }
            if (completed < required.size()) {
                RealtimeLocalisedWeatherMod.LOGGER.warn("Realtime weather fetch returned {} location(s) for {} requested tile(s)", locations.size(), required.size());
            }
            state.lastSuccessfulRefresh = Instant.now();
            syncPlayers(level, state, false);
        }));
    }

    private ServerWeatherSnapshot buildSnapshot(ServerLevel level, LevelState state, WeatherTileKey key, OpenMeteoResponse.LocationWeather location, long revision) {
        int tileSize = ServerWeatherConfig.ZONE_SIZE_BLOCKS.get();
        int blockX = key.tileX() * tileSize + tileSize / 2;
        int blockZ = key.tileZ() * tileSize + tileSize / 2;
        SereneSeasonSnapshot seasonSnapshot = SereneSeasonsCompat.snapshot(level, level.getBiome(new BlockPos(blockX, level.getSeaLevel(), blockZ)));
        state.currentSeason = seasonSnapshot.season();
        state.currentSubSeason = seasonSnapshot.subSeason();
        var resolution = SerenePrecipitationResolver.resolve(
            location.meteorologicalPrecipitation(),
            location.temperature(),
            location.snowfall(),
            seasonSnapshot
        );
        float thunderPotential = switch (location.weatherCode()) {
            case 95 -> 0.8F;
            case 96, 99 -> 1.0F;
            default -> location.showers() > 0.2F ? 0.35F : 0.0F;
        };
        return new ServerWeatherSnapshot(
            location.observedAt(),
            location.latitude(),
            location.longitude(),
            location.weatherCode(),
            location.meteorologicalPrecipitation(),
            location.precipitation(),
            location.rain(),
            location.snowfall(),
            location.temperature(),
            location.humidity(),
            location.visibility(),
            location.windSpeed(),
            location.windDirection(),
            location.windGusts(),
            resolution.resolvedPrecipitation(),
            WeatherSeverityMapper.fromRates(location.precipitation(), location.snowfall(), thunderPotential),
            thunderPotential,
            false,
            revision
        );
    }

    private ServerWeatherSnapshot resolvedSnapshotFor(ServerLevel level, LevelState state, WeatherTileKey key, BlockPos position) {
        long now = System.currentTimeMillis();
        ServerWeatherSnapshot base = state.baseSnapshotFor(key).orElse(null);
        Optional<VisualOverrideManager.Entry> visualOverride = state.visualOverrideManager.lookup(key);
        if (base == null) {
            return visualOverride.map(entry -> entry.apply(placeholderSnapshot())).orElse(null);
        }
        Optional<WeatherOverrideManager.OverrideEntry> override = state.overrideManager.lookup(key, now);
        if (override.isPresent()) {
            base = override.get().toSnapshot(base, base.revision() + 1L);
        }
        if (visualOverride.isPresent()) return visualOverride.get().apply(base);
        SereneSeasonSnapshot seasonSnapshot = SereneSeasonsCompat.snapshot(level, level.getBiome(position));
        var resolution = SerenePrecipitationResolver.resolve(
            base.precipitation(),
            base.temperatureCelsius(),
            base.snowfallRateCmPerHour(),
            seasonSnapshot
        );
        if (resolution.resolvedPrecipitation() == base.resolvedPrecipitation()) {
            return base;
        }
        return new ServerWeatherSnapshot(
            base.observedAt(),
            base.latitude(),
            base.longitude(),
            base.weatherCode(),
            base.precipitation(),
            base.precipitationRateMmPerHour(),
            base.rainRateMmPerHour(),
            base.snowfallRateCmPerHour(),
            base.temperatureCelsius(),
            base.relativeHumidity(),
            base.visibilityMetres(),
            base.windSpeedKmh(),
            base.windDirectionDegrees(),
            base.windGustKmh(),
            resolution.resolvedPrecipitation(),
            base.gameplaySeverity(),
            base.thunderPotential(),
            base.stale(),
            base.revision()
        );
    }

    private void tickGameplay(ServerLevel level, LevelState state) {
        for (ServerPlayer player : level.players()) {
            BlockPos playerPos = player.blockPosition();
            int chunkRadius = Math.max(2, ServerWeatherConfig.ACTIVE_ZONE_RADIUS.get() * ServerWeatherConfig.ZONE_SIZE_BLOCKS.get() / 16);
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(player.chunkPosition().x + dx, player.chunkPosition().z + dz);
                    if (chunk == null) {
                        continue;
                    }
                    sampleGameplayChunk(level, chunk);
                }
            }
            if (isThunderingAt(level, playerPos.above(40)) && level.random.nextFloat() < 0.05F && ServerWeatherConfig.ENABLE_AUTHORITATIVE_LIGHTNING.get()) {
                spawnLightning(level, playerPos.offset(level.random.nextInt(96) - 48, 0, level.random.nextInt(96) - 48));
            }
        }
    }

    private void sampleGameplayChunk(ServerLevel level, LevelChunk chunk) {
        for (int i = 0; i < 2; i++) {
            int localX = level.random.nextInt(16);
            int localZ = level.random.nextInt(16);
            int blockX = chunk.getPos().getMinBlockX() + localX;
            int blockZ = chunk.getPos().getMinBlockZ() + localZ;
            int topY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, blockX, blockZ);
            BlockPos topPos = new BlockPos(blockX, topY, blockZ);
            if (!level.canSeeSky(topPos)) {
                continue;
            }
            LocalWeatherState weather = getWeatherAt(level, topPos);
            if (weather.snapshot() != null
                && !weather.snapshot().resolvedPrecipitation().isSnowy()) {
                if (ServerWeatherConfig.ENABLE_SNOW_ACCUMULATION.get()) {
                    net.minecraft.world.level.block.state.BlockState surface = level.getBlockState(topPos);
                    if (surface.is(net.minecraft.world.level.block.Blocks.SNOW)) {
                        int layers = surface.getValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS);
                        double meltDays = ServerWeatherConfig.SNOW_MELT_MINECRAFT_DAYS.get();
                        double meltChance = Math.min(1.0D, layers / (GAMEPLAY_COLUMN_SAMPLES_PER_MINECRAFT_DAY * meltDays));
                        if (level.random.nextFloat() < meltChance) {
                            meltSnowAt(level, topPos);
                        }
                    }
                }
                if (ServerWeatherConfig.ENABLE_ICE_FORMATION.get()) {
                    BlockPos icePos = null;
                    if (level.getBlockState(topPos).is(net.minecraft.world.level.block.Blocks.ICE)) {
                        icePos = topPos;
                    } else if (level.getBlockState(topPos.below()).is(net.minecraft.world.level.block.Blocks.ICE)) {
                        icePos = topPos.below();
                    }
                    if (icePos != null) {
                        double meltChance = Math.min(1.0D, 1.0D / (GAMEPLAY_COLUMN_SAMPLES_PER_MINECRAFT_DAY * ServerWeatherConfig.ICE_MELT_MINECRAFT_DAYS.get()));
                        if (level.random.nextFloat() < meltChance) {
                            meltIceAt(level, icePos);
                        }
                    }
                }
            }
            if (weather.snapshot() == null || !weather.snapshot().hasPrecipitation()) {
                continue;
            }
            if (ServerWeatherConfig.ENABLE_FIRE_EXTINGUISHING.get() && level.getBlockState(topPos.below()).is(net.minecraft.world.level.block.Blocks.FIRE)) {
                level.removeBlock(topPos.below(), false);
            }
            if (ServerWeatherConfig.ENABLE_CAULDRON_FILLING.get()
                && weather.snapshot().resolvedPrecipitation().isLiquid()
                && level.getBlockState(topPos.below()).is(net.minecraft.world.level.block.Blocks.CAULDRON)
                && level.random.nextFloat() < 0.02F * WeatherSeverityMapper.toGameplayMultiplier(weather.snapshot().gameplaySeverity())) {
                level.setBlockAndUpdate(topPos.below(), net.minecraft.world.level.block.Blocks.WATER_CAULDRON.defaultBlockState());
            }
            if (ServerWeatherConfig.ENABLE_SNOW_ACCUMULATION.get()
                && weather.snapshot().resolvedPrecipitation().isSnowy()
                && level.random.nextFloat() < 0.03F * WeatherSeverityMapper.toGameplayMultiplier(weather.snapshot().gameplaySeverity())) {
                accumulateSnowAt(level, topPos);
            }
            if (ServerWeatherConfig.ENABLE_ICE_FORMATION.get()
                && weather.snapshot().resolvedPrecipitation().isSnowy()
                && level.getBlockState(topPos.below()).is(net.minecraft.world.level.block.Blocks.WATER)
                && level.random.nextFloat() < 0.02F) {
                level.setBlockAndUpdate(topPos.below(), net.minecraft.world.level.block.Blocks.ICE.defaultBlockState());
            }
        }
        AABB chunkBox = new AABB(chunk.getPos().getMinBlockX(), level.getMinBuildHeight(), chunk.getPos().getMinBlockZ(), chunk.getPos().getMaxBlockX() + 1, level.getMaxBuildHeight(), chunk.getPos().getMaxBlockZ() + 1);
        level.getEntities(null, chunkBox).forEach(entity -> {
            if (entity.isOnFire() && isRainingAt(level, entity.blockPosition()) && level.canSeeSky(entity.blockPosition())) {
                entity.clearFire();
            }
        });
    }

    private void accumulateSnowAt(ServerLevel level, BlockPos topPos) {
        // MOTION_BLOCKING height is the air cell above the surface — place/thicken snow there.
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(topPos);
        if (state.is(net.minecraft.world.level.block.Blocks.SNOW)) {
            int layers = state.getValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS);
            if (layers < 8) {
                level.setBlockAndUpdate(topPos, state.setValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS, layers + 1));
            }
            return;
        }
        if (state.isAir() && net.minecraft.world.level.block.Blocks.SNOW.defaultBlockState().canSurvive(level, topPos)) {
            level.setBlockAndUpdate(topPos, net.minecraft.world.level.block.Blocks.SNOW.defaultBlockState());
        }
    }

    private void meltSnowAt(ServerLevel level, BlockPos topPos) {
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(topPos);
        if (!state.is(net.minecraft.world.level.block.Blocks.SNOW)) {
            return;
        }
        int layers = state.getValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS);
        if (layers <= 1) {
            level.removeBlock(topPos, false);
        } else {
            level.setBlockAndUpdate(topPos, state.setValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS, layers - 1));
        }
    }

    private void meltIceAt(ServerLevel level, BlockPos icePos) {
        if (level.getBlockState(icePos).is(net.minecraft.world.level.block.Blocks.ICE)) {
            level.setBlockAndUpdate(icePos, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
        }
    }

    private void spawnLightning(ServerLevel level, BlockPos position) {
        int topY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, position.getX(), position.getZ());
        BlockPos strikePos = new BlockPos(position.getX(), topY, position.getZ());
        if (!level.canSeeSky(strikePos)) {
            return;
        }
        LightningBolt lightningBolt = EntityType.LIGHTNING_BOLT.create(level);
        if (lightningBolt == null) {
            return;
        }
        lightningBolt.moveTo(strikePos.getX() + 0.5D, strikePos.getY(), strikePos.getZ() + 0.5D);
        level.addFreshEntity(lightningBolt);
        PacketDistributor.sendToAllPlayers(new WeatherLightningPayload(level.dimension().location().toString(), strikePos.getX(), strikePos.getY(), strikePos.getZ(), true));
    }

    private void syncPlayers(ServerLevel level, LevelState state, boolean fullResync) {
        for (ServerPlayer player : level.players()) {
            syncPlayer(level, state, player, fullResync, true);
        }
    }

    private void syncPlayer(ServerLevel level, LevelState state, ServerPlayer player, boolean fullResync) {
        syncPlayer(level, state, player, fullResync, true);
    }

    private void syncPlayer(ServerLevel level, LevelState state, ServerPlayer player, boolean fullResync, boolean includeHandshake) {
        Set<WeatherTileKey> relevant = ActiveTileTracker.collect(level.dimension(), List.of(player.blockPosition()), ServerWeatherConfig.ZONE_SIZE_BLOCKS.get(), ServerWeatherConfig.ACTIVE_ZONE_RADIUS.get(), ServerWeatherConfig.PREFETCH_ZONE_RADIUS.get());
        Set<Long> relevantPacked = new HashSet<>();
        List<TileSnapshotPayload> payloadTiles = new ArrayList<>();
        for (WeatherTileKey key : relevant) {
            relevantPacked.add(pack(key.tileX(), key.tileZ()));
            ServerWeatherSnapshot snapshot = resolvedSnapshotFor(level, state, key, player.blockPosition());
            ManagedTile tile = state.tiles.get(key);
            if (snapshot != null && tile != null) {
                payloadTiles.add(new TileSnapshotPayload(key.tileX(), key.tileZ(), tile.visualSeed, snapshot));
            }
        }
        PlayerSyncState playerState = state.playerSync.computeIfAbsent(player.getUUID(), ignored -> new PlayerSyncState());
        boolean initial = fullResync || playerState.sentRevisions.isEmpty();
        if (includeHandshake || initial) {
            sendProtocolAndReference(player, state);
        }
        if (initial) {
            PacketDistributor.sendToPlayer(player, new WeatherInitialGridPayload(level.dimension().location().toString(), state.mode, state.currentSeason, state.currentSubSeason, payloadTiles));
            playerState.replace(payloadTiles, relevantPacked);
            return;
        }
        List<TileSnapshotPayload> updates = new ArrayList<>();
        for (TileSnapshotPayload payloadTile : payloadTiles) {
            long packed = pack(payloadTile.tileX(), payloadTile.tileZ());
            long previousRevision = playerState.sentRevisions.getOrDefault(packed, Long.MIN_VALUE);
            if (previousRevision != payloadTile.snapshot().revision()) {
                updates.add(payloadTile);
                playerState.sentRevisions.put(packed, payloadTile.snapshot().revision());
            }
        }
        Set<Long> removed = new HashSet<>(playerState.sentRevisions.keySet());
        removed.removeAll(relevantPacked);
        if (!updates.isEmpty()) {
            PacketDistributor.sendToPlayer(player, new WeatherTileUpdatePayload(level.dimension().location().toString(), updates));
        }
        if (!removed.isEmpty()) {
            PacketDistributor.sendToPlayer(player, new WeatherTileRemovePayload(level.dimension().location().toString(), List.copyOf(removed)));
            removed.forEach(playerState.sentRevisions::remove);
        }
    }

    private void sendProtocolAndReference(ServerPlayer player, LevelState state) {
        PacketDistributor.sendToPlayer(player, new WeatherProtocolPayload(RealtimeLocalisedWeatherMod.PROTOCOL_VERSION, RealtimeLocalisedWeatherMod.MOD_VERSION));
        UkGeoReference reference = state.reference;
        if (reference != null) {
            PacketDistributor.sendToPlayer(player, new UkGeoReferencePayload(
                player.serverLevel().dimension().location().toString(),
                reference.crs(),
                reference.minecraftMinX(),
                reference.minecraftMinZ(),
                reference.minecraftMaxX(),
                reference.minecraftMaxZ(),
                reference.bngMinEasting(),
                reference.bngMinNorthing(),
                reference.bngMaxEasting(),
                reference.bngMaxNorthing(),
                reference.rasterWidth(),
                reference.rasterDepth(),
                ServerWeatherConfig.ZONE_SIZE_BLOCKS.get()
            ));
        } else {
            // Fallback for /rain debug before UKGeo reference exists. Client sampling only needs zone size.
            PacketDistributor.sendToPlayer(player, new UkGeoReferencePayload(
                player.serverLevel().dimension().location().toString(),
                "EPSG:27700",
                Integer.MIN_VALUE / 4,
                Integer.MIN_VALUE / 4,
                Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4,
                0.0D,
                0.0D,
                1.0D,
                1.0D,
                1,
                1,
                ServerWeatherConfig.ZONE_SIZE_BLOCKS.get()
            ));
        }
        PacketDistributor.sendToPlayer(player, new WeatherAuthorityModePayload(player.serverLevel().dimension().location().toString(), state.mode));
    }

    private void sendInitialSync(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Optional<UkGeoReference> reference = UkGeoReferenceProvider.get(level);
        if (reference.isEmpty()) {
            return;
        }
        LevelState state = levelStates.computeIfAbsent(level.dimension(), ignored -> new LevelState(reference.get()));
        syncPlayer(level, state, player, true);
    }

    private void clearPlayerTracking(UUID uuid) {
        for (LevelState state : levelStates.values()) {
            state.playerSync.remove(uuid);
        }
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static long randomSeed(WeatherTileKey key) {
        return 31L * key.tileX() + 1315423911L * key.tileZ() + key.dimension().location().hashCode();
    }

    private static ServerWeatherSnapshot stale(ServerWeatherSnapshot snapshot) {
        return new ServerWeatherSnapshot(
            snapshot.observedAt(),
            snapshot.latitude(),
            snapshot.longitude(),
            snapshot.weatherCode(),
            snapshot.precipitation(),
            snapshot.precipitationRateMmPerHour(),
            snapshot.rainRateMmPerHour(),
            snapshot.snowfallRateCmPerHour(),
            snapshot.temperatureCelsius(),
            snapshot.relativeHumidity(),
            snapshot.visibilityMetres(),
            snapshot.windSpeedKmh(),
            snapshot.windDirectionDegrees(),
            snapshot.windGustKmh(),
            snapshot.resolvedPrecipitation(),
            snapshot.gameplaySeverity(),
            snapshot.thunderPotential(),
            true,
            snapshot.revision()
        );
    }

    private record StaleCandidate(WeatherTileKey key, Instant observedAt, boolean neverSucceeded) {}

    private static final class LevelState {
        private final WeatherMemoryCache memoryCache = new WeatherMemoryCache();
        private final Map<WeatherTileKey, ManagedTile> tiles = new HashMap<>();
        private final Map<WeatherTileKey, CompletableFuture<List<OpenMeteoResponse.LocationWeather>>> pendingFetches = new HashMap<>();
        private NationalWeatherGrid nationalGrid;
        private CompletableFuture<Boolean> pendingNationalRefresh;
        private CompletableFuture<Void> pendingRainViewerRefresh;
        private final Map<UUID, PlayerSyncState> playerSync = new HashMap<>();
        private final WeatherOverrideManager overrideManager = new WeatherOverrideManager();
        private final VisualOverrideManager visualOverrideManager = new VisualOverrideManager();
        private UkGeoReference reference;
        private WeatherAuthorityMode mode = ServerWeatherConfig.AUTHORITY_MODE.get();
        private Set<WeatherTileKey> activeTiles = Set.of();
        private Instant lastSuccessfulRefresh = Instant.EPOCH;
        private Instant nextFetchAllowed = Instant.EPOCH;
        private Instant nextFetchFailureLogAllowed = Instant.EPOCH;
        private Duration fetchFailureBackoff = Duration.ZERO;
        private boolean refreshRequested;
        private String currentSeason = "unknown";
        private String currentSubSeason = "unknown";

        private LevelState(UkGeoReference reference) {
            this.reference = reference; // may be null for /rain debug before UKGeo is ready
        }

        private Optional<ServerWeatherSnapshot> baseSnapshotFor(WeatherTileKey key) {
            ManagedTile tile = tiles.get(key);
            return tile == null ? Optional.empty() : Optional.ofNullable(tile.snapshot);
        }

        private int staleTileCount() {
            int count = 0;
            for (ManagedTile tile : tiles.values()) {
                if (tile.snapshot != null && tile.snapshot.stale()) {
                    count++;
                }
            }
            return count;
        }
    }

    private static final class ManagedTile {
        private ServerWeatherSnapshot snapshot;
        private long revision;
        private long lastTouchedMillis;
        private final long visualSeed;

        private ManagedTile(ServerWeatherSnapshot snapshot, long revision, long lastTouchedMillis, long visualSeed) {
            this.snapshot = snapshot;
            this.revision = revision;
            this.lastTouchedMillis = lastTouchedMillis;
            this.visualSeed = visualSeed;
        }
    }

    private static final class PlayerSyncState {
        private final Map<Long, Long> sentRevisions = new HashMap<>();

        private void replace(List<TileSnapshotPayload> tiles, Set<Long> relevantPacked) {
            sentRevisions.clear();
            for (TileSnapshotPayload tile : tiles) {
                sentRevisions.put(pack(tile.tileX(), tile.tileZ()), tile.snapshot().revision());
            }
            relevantPacked.forEach(key -> sentRevisions.putIfAbsent(key, Long.MIN_VALUE));
        }
    }
}
