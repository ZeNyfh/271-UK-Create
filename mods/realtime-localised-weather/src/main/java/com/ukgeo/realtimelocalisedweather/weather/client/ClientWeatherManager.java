package com.ukgeo.realtimelocalisedweather.weather.client;

import com.ukgeo.realtimelocalisedweather.config.ClientWeatherConfig;
import com.ukgeo.realtimelocalisedweather.network.TileSnapshotPayload;
import com.ukgeo.realtimelocalisedweather.network.UkGeoReferencePayload;
import com.ukgeo.realtimelocalisedweather.network.WeatherAuthorityModePayload;
import com.ukgeo.realtimelocalisedweather.network.WeatherInitialGridPayload;
import com.ukgeo.realtimelocalisedweather.network.WeatherLightningPayload;
import com.ukgeo.realtimelocalisedweather.network.WeatherProtocolPayload;
import com.ukgeo.realtimelocalisedweather.network.WeatherTileRemovePayload;
import com.ukgeo.realtimelocalisedweather.network.WeatherTileUpdatePayload;
import com.ukgeo.realtimelocalisedweather.weather.ServerWeatherSnapshot;
import com.ukgeo.realtimelocalisedweather.weather.WeatherAuthorityMode;
import com.ukgeo.realtimelocalisedweather.weather.WeatherMath;
import com.ukgeo.realtimelocalisedweather.weather.WeatherTileKey;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

public final class ClientWeatherManager {
    private static final Map<ResourceKey<Level>, Map<Long, ClientWeatherTile>> TILES = new HashMap<>();
    private static final Map<ResourceKey<Level>, ReferenceData> REFERENCES = new HashMap<>();
    private static final Map<ResourceKey<Level>, WeatherAuthorityMode> MODES = new HashMap<>();
    private static volatile String lastServerProtocol = "";

    private ClientWeatherManager() {
    }

    public static void receiveProtocol(WeatherProtocolPayload payload) {
        lastServerProtocol = payload.protocolVersion();
    }

    public static void receiveReference(UkGeoReferencePayload payload) {
        REFERENCES.put(key(payload.dimension()), new ReferenceData(payload));
    }

    public static void receiveInitialGrid(WeatherInitialGridPayload payload) {
        ResourceKey<Level> dimension = key(payload.dimension());
        MODES.put(dimension, payload.mode());
        Map<Long, ClientWeatherTile> dimensionTiles = TILES.computeIfAbsent(dimension, ignored -> new HashMap<>());
        dimensionTiles.clear();
        long now = System.currentTimeMillis();
        for (TileSnapshotPayload tile : payload.tiles()) {
            WeatherTileKey key = new WeatherTileKey(dimension, tile.tileX(), tile.tileZ());
            dimensionTiles.put(pack(tile.tileX(), tile.tileZ()), new ClientWeatherTile(key, tile.snapshot(), tile.snapshot(), now, tile.visualSeed()));
        }
    }

    public static void receiveUpdates(WeatherTileUpdatePayload payload) {
        ResourceKey<Level> dimension = key(payload.dimension());
        Map<Long, ClientWeatherTile> dimensionTiles = TILES.computeIfAbsent(dimension, ignored -> new HashMap<>());
        long now = System.currentTimeMillis();
        for (TileSnapshotPayload tile : payload.tiles()) {
            long packed = pack(tile.tileX(), tile.tileZ());
            ClientWeatherTile previous = dimensionTiles.get(packed);
            ServerWeatherSnapshot previousSnapshot = previous == null ? tile.snapshot() : previous.current();
            dimensionTiles.put(
                packed,
                new ClientWeatherTile(new WeatherTileKey(dimension, tile.tileX(), tile.tileZ()), previousSnapshot, tile.snapshot(), now, tile.visualSeed())
            );
        }
    }

    public static void receiveRemovals(WeatherTileRemovePayload payload) {
        Map<Long, ClientWeatherTile> dimensionTiles = TILES.get(key(payload.dimension()));
        if (dimensionTiles == null) {
            return;
        }
        for (Long packedKey : payload.packedTileKeys()) {
            dimensionTiles.remove(packedKey);
        }
    }

    public static void receiveMode(WeatherAuthorityModePayload payload) {
        MODES.put(key(payload.dimension()), payload.mode());
    }

    public static void receiveLightning(WeatherLightningPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.level.dimension().location().toString().equals(payload.dimension())) {
            minecraft.level.addAlwaysVisibleParticle(net.minecraft.core.particles.ParticleTypes.FLASH, payload.blockX(), payload.blockY(), payload.blockZ(), 0.0D, 0.0D, 0.0D);
        }
    }

    public static boolean shouldReplaceVanillaWeather() {
        return ClientWeatherConfig.ENABLED.get()
            && ClientWeatherConfig.PRECIPITATION_RENDERER.get() == ClientWeatherConfig.PrecipitationRendererMode.CUSTOM
            && activeMode() != WeatherAuthorityMode.VANILLA;
    }

    public static boolean shouldReplaceClouds() {
        return ClientWeatherConfig.ENABLED.get()
            && ClientWeatherConfig.CLOUD_RENDERER.get() == ClientWeatherConfig.CloudRendererMode.REPLACE
            && activeMode() != WeatherAuthorityMode.VANILLA;
    }

    public static Optional<VisualWeatherSample> sample(BlockPos position) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || position == null) {
            return Optional.empty();
        }
        ResourceKey<Level> dimension = minecraft.level.dimension();
        ReferenceData referenceData = REFERENCES.get(dimension);
        if (referenceData == null) {
            return Optional.empty();
        }
        int tileSize = referenceData.zoneSizeBlocks;
        int tileX = Math.floorDiv(position.getX(), tileSize);
        int tileZ = Math.floorDiv(position.getZ(), tileSize);
        Map<Long, ClientWeatherTile> tiles = TILES.get(dimension);
        if (tiles == null) {
            return Optional.empty();
        }
        ClientWeatherTile center = tiles.get(pack(tileX, tileZ));
        if (center == null) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        ServerWeatherSnapshot centerSnapshot = WeatherInterpolator.interpolateSnapshot(center, now);
        float offsetX = (float) Math.floorMod(position.getX(), tileSize) / tileSize;
        float offsetZ = (float) Math.floorMod(position.getZ(), tileSize) / tileSize;
        ServerWeatherSnapshot east = snapshotOr(center, tiles.get(pack(tileX + 1, tileZ)), now);
        ServerWeatherSnapshot south = snapshotOr(center, tiles.get(pack(tileX, tileZ + 1)), now);
        ServerWeatherSnapshot southEast = snapshotOr(center, tiles.get(pack(tileX + 1, tileZ + 1)), now);
        float rate = WeatherMath.bilinear(
            centerSnapshot.precipitationRateMmPerHour(),
            east.precipitationRateMmPerHour(),
            south.precipitationRateMmPerHour(),
            southEast.precipitationRateMmPerHour(),
            offsetX,
            offsetZ
        );
        float cloud = WeatherMath.bilinear(
            centerSnapshot.totalCloudCover(),
            east.totalCloudCover(),
            south.totalCloudCover(),
            southEast.totalCloudCover(),
            offsetX,
            offsetZ
        );
        return Optional.of(new VisualWeatherSample(centerSnapshot, rate, cloud, referenceData.zoneSizeBlocks));
    }

    public static String lastServerProtocol() {
        return lastServerProtocol;
    }

    public static WeatherAuthorityMode activeMode() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return WeatherAuthorityMode.VANILLA;
        }
        return MODES.getOrDefault(minecraft.level.dimension(), WeatherAuthorityMode.VANILLA);
    }

    private static ServerWeatherSnapshot snapshotOr(ClientWeatherTile fallback, ClientWeatherTile candidate, long now) {
        return candidate == null ? WeatherInterpolator.interpolateSnapshot(fallback, now) : WeatherInterpolator.interpolateSnapshot(candidate, now);
    }

    private static ResourceKey<Level> key(String dimension) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension));
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private record ReferenceData(int zoneSizeBlocks) {
        ReferenceData(UkGeoReferencePayload payload) {
            this(payload.zoneSizeBlocks());
        }
    }

    public record VisualWeatherSample(ServerWeatherSnapshot snapshot, float interpolatedRate, float interpolatedCloudCover, int zoneSizeBlocks) {
    }
}
