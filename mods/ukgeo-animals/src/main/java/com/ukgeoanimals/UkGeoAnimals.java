package com.ukgeoanimals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(UkGeoAnimals.MOD_ID)
public final class UkGeoAnimals {
    public static final String MOD_ID = "ukgeo_animals";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final boolean DEBUG_ANIMAL_SPAWNS = Boolean.getBoolean("ukgeoanimals.debugSpawns");
    private static final boolean DEBUG_PERF = Boolean.getBoolean("ukgeoAnimals.debugPerf");
    private static final ResourceLocation BLUNDERBUSS = ResourceLocation.fromNamespaceAndPath("wildernature", "blunderbuss");
    private static final Map<ResourceLocation, Boolean> UKGEO_BIOME_CACHE = new ConcurrentHashMap<>();
    private static final LongAdder SPAWN_CHECKS = new LongAdder();
    private static final LongAdder BLOCKED_SPAWNS = new LongAdder();
    private static final Object ANIMAL_HABITAT_LOCK = new Object();
    private static volatile AnimalHabitatRuntime ANIMAL_HABITAT_RUNTIME;

    private static final Set<ResourceLocation> UNWANTED_WILDERNATURE = Set.of(
            id("wildernature", "red_wolf"),
            id("wildernature", "raccoon"),
            id("wildernature", "penguin"),
            id("wildernature", "turkey"),
            id("wildernature", "pelican"),
            id("wildernature", "flamingo"),
            id("wildernature", "cassowary")
    );

    private static final Set<ResourceLocation> FILTERED_WILDERNATURE = Set.of(
            id("wildernature", "deer"),
            id("wildernature", "bison"),
            id("wildernature", "boar"),
            id("wildernature", "dog"),
            id("wildernature", "hedgehog"),
            id("wildernature", "minisheep"),
            id("wildernature", "owl"),
            id("wildernature", "squirrel")
    );

    private static final Set<ResourceLocation> FILTERED_VANILLA = Set.of(
            id("minecraft", "cow"),
            id("minecraft", "sheep"),
            id("minecraft", "pig"),
            id("minecraft", "chicken"),
            id("minecraft", "rabbit"),
            id("minecraft", "wolf"),
            id("minecraft", "fox"),
            id("minecraft", "bat")
    );

    private static final Map<ResourceLocation, Integer> EXTRA_RARITY = Map.of(
            id("wildernature", "bison"), 10,
            id("wildernature", "dog"), 5,
            id("wildernature", "owl"), 3,
            id("minecraft", "wolf"), 6,
            id("minecraft", "fox"), 3,
            id("minecraft", "bat"), 4
    );

    public UkGeoAnimals(IEventBus modBus, ModContainer modContainer) {
        modBus.addListener(UkGeoAnimals::removeBlunderbussFromCreativeTabs);
        NeoForge.EVENT_BUS.register(this);
        validateWilderNatureEntityIds();
        LOGGER.info("UKGeo Animals loaded");
    }

    private static void validateWilderNatureEntityIds() {
        if (!DEBUG_ANIMAL_SPAWNS) {
            return;
        }
        for (ResourceLocation id : FILTERED_WILDERNATURE) {
            LOGGER.info("UKGeo Animals debug wanted entity {} exists={}", id, BuiltInRegistries.ENTITY_TYPE.containsKey(id));
        }
        for (ResourceLocation id : UNWANTED_WILDERNATURE) {
            LOGGER.info("UKGeo Animals debug blocked entity {} exists={}", id, BuiltInRegistries.ENTITY_TYPE.containsKey(id));
        }
    }

    private static void removeBlunderbussFromCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        Item item = BuiltInRegistries.ITEM.getOptional(BLUNDERBUSS).orElse(null);
        if (item == null) {
            return;
        }
        ItemStack stack = item.getDefaultInstance();
        event.remove(stack, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
        event.remove(stack, CreativeModeTab.TabVisibility.PARENT_TAB_ONLY);
        event.remove(stack, CreativeModeTab.TabVisibility.SEARCH_TAB_ONLY);
    }

    @SubscribeEvent
    public void onSpawnPlacement(MobSpawnEvent.SpawnPlacementCheck event) {
        ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(event.getEntityType());
        BlockPos pos = event.getPos();
        ResourceLocation biomeId = biomeId(event.getLevel(), pos);
        recordSpawnCheck();
        if (!isUkGeoBiome(biomeId)) {
            return;
        }

        SpawnDecision decision = decide(entityId, biomeId.getPath(), pos, event.getSpawnType(), event.getLevel());
        if (!decision.allowed()) {
            BLOCKED_SPAWNS.increment();
            debug("placement blocked entity=%s biome=%s reason=%s spawn=%s y=%d", entityId, biomeId, decision.reason(), event.getSpawnType(), pos.getY());
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }

    @SubscribeEvent
    public void onPositionCheck(MobSpawnEvent.PositionCheck event) {
        ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(event.getEntity().getType());
        BlockPos pos = BlockPos.containing(event.getX(), event.getY(), event.getZ());
        ResourceLocation biomeId = biomeId(event.getLevel(), pos);
        recordSpawnCheck();
        if (!isUkGeoBiome(biomeId)) {
            return;
        }

        SpawnDecision decision = decide(entityId, biomeId.getPath(), pos, event.getSpawnType(), event.getLevel());
        if (!decision.allowed()) {
            BLOCKED_SPAWNS.increment();
            debug("position blocked entity=%s biome=%s reason=%s spawn=%s y=%d", entityId, biomeId, decision.reason(), event.getSpawnType(), pos.getY());
            event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
        }
    }

    private static SpawnDecision decide(ResourceLocation entityId, String biome, BlockPos pos, MobSpawnType spawnType, net.minecraft.world.level.ServerLevelAccessor level) {
        if (UNWANTED_WILDERNATURE.contains(entityId) && isWorldSpawn(spawnType)) {
            return SpawnDecision.block("unwanted_wildernature");
        }
        if (!isWorldSpawn(spawnType)) {
            return SpawnDecision.allow();
        }
        if (FILTERED_WILDERNATURE.contains(entityId) || FILTERED_VANILLA.contains(entityId)) {
            if (!isHabitatAllowed(entityId, biome, pos, spawnType, level)) {
                return SpawnDecision.block("habitat");
            }
            if (!isAdditionalSpawnConditionAllowed(entityId, pos, spawnType, level)) {
                return SpawnDecision.block("conditions");
            }
            int rarity = EXTRA_RARITY.getOrDefault(entityId, 1);
            if (rarity > 1 && Math.floorMod(positionHash(pos, entityId.hashCode()), rarity) != 0) {
                return SpawnDecision.block("rarity");
            }
        }
        return SpawnDecision.allow();
    }

    private static boolean isWorldSpawn(MobSpawnType spawnType) {
        return spawnType == MobSpawnType.NATURAL
                || spawnType == MobSpawnType.CHUNK_GENERATION
                || spawnType == MobSpawnType.STRUCTURE
                || spawnType == MobSpawnType.EVENT
                || spawnType == MobSpawnType.MOB_SUMMONED
                || spawnType == MobSpawnType.TRIGGERED
                || spawnType == MobSpawnType.PATROL;
    }

    private static boolean isHabitatAllowed(ResourceLocation entityId, String biome, BlockPos pos, MobSpawnType spawnType, net.minecraft.world.level.ServerLevelAccessor level) {
        AnimalHabitatRuntime runtime = animalHabitatRuntime();
        if (runtime.enabled()) {
            HabitatLayer layer = runtime.layers().get(entityId);
            if (layer == null) {
                return false;
            }
            return layer.sampleOrDefault(pos.getX(), pos.getZ(), 0) > 0;
        }
        return isLegacyHabitatAllowed(entityId, biome, pos, spawnType, level);
    }

    private static boolean isLegacyHabitatAllowed(ResourceLocation entityId, String biome, BlockPos pos, MobSpawnType spawnType, net.minecraft.world.level.ServerLevelAccessor level) {
        int y = pos.getY();
        if (isWaterOrWetland(biome)) {
            return entityId.equals(id("minecraft", "bat"));
        }
        return switch (entityId.getNamespace() + ":" + entityId.getPath()) {
            case "wildernature:deer" -> y <= 265 && (isWoodland(biome) || isGrassland(biome) || isHeath(biome) || isArableEdge(biome));
            case "wildernature:boar" -> y <= 245 && (isWoodland(biome) || isHeath(biome) || isArableEdge(biome));
            case "wildernature:hedgehog" -> y <= 185 && (isLowlandOpen(biome) || isArableEdge(biome) || "urban".equals(biome) || "broadleaf_woodland".equals(biome));
            case "wildernature:squirrel" -> y <= 235 && (isWoodland(biome) || "urban".equals(biome));
            case "wildernature:owl" -> y <= 265 && (isWoodland(biome) || isArableEdge(biome) || isHeath(biome));
            case "wildernature:dog" -> y <= 170 && (isLowlandOpen(biome) || "urban".equals(biome) || "arable".equals(biome));
            case "wildernature:minisheep" -> y <= 290 && (isGrassland(biome) || isHeath(biome) || "rocky".equals(biome));
            case "wildernature:bison" -> y <= 220 && ("improved_grassland".equals(biome) || "neutral_grassland".equals(biome) || isHeath(biome));
            case "minecraft:cow" -> y <= 210 && (isLowlandOpen(biome) || "arable".equals(biome) || "urban".equals(biome));
            case "minecraft:sheep" -> y <= 290 && (isGrassland(biome) || isHeath(biome) || "rocky".equals(biome) || "arable".equals(biome) || "urban".equals(biome));
            case "minecraft:pig" -> y <= 230 && (isWoodland(biome) || isArableEdge(biome) || isLowlandOpen(biome));
            case "minecraft:chicken" -> y <= 220 && (isLowlandOpen(biome) || isArableEdge(biome) || isWoodland(biome) || "urban".equals(biome));
            case "minecraft:rabbit" -> y <= 285 && (isGrassland(biome) || isHeath(biome) || "rocky".equals(biome) || "arable".equals(biome));
            case "minecraft:wolf" -> y >= 80 && y <= 260 && (isWoodland(biome) || isHeath(biome) || "acid_grassland".equals(biome));
            case "minecraft:fox" -> y <= 245 && (spawnType == MobSpawnType.CHUNK_GENERATION || isLowLightOrNight(level, pos)) && (isWoodland(biome) || isArableEdge(biome) || isHeath(biome));
            case "minecraft:bat" -> y <= 120 && (spawnType == MobSpawnType.CHUNK_GENERATION || isLowLightOrNight(level, pos));
            default -> true;
        };
    }

    private static boolean isAdditionalSpawnConditionAllowed(ResourceLocation entityId, BlockPos pos, MobSpawnType spawnType, net.minecraft.world.level.ServerLevelAccessor level) {
        return switch (entityId.getNamespace() + ":" + entityId.getPath()) {
            case "minecraft:fox", "minecraft:bat" -> spawnType == MobSpawnType.CHUNK_GENERATION || isLowLightOrNight(level, pos);
            default -> true;
        };
    }

    private static boolean isLowLightOrNight(net.minecraft.world.level.ServerLevelAccessor level, BlockPos pos) {
        return level.getLevel().isNight() || level.getLevel().getBrightness(LightLayer.BLOCK, pos) <= 7;
    }

    private static ResourceLocation biomeId(net.minecraft.world.level.ServerLevelAccessor level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey().map(ResourceKey::location).orElse(null);
    }

    private static boolean isUkGeoBiome(ResourceLocation biomeId) {
        return biomeId != null && UKGEO_BIOME_CACHE.computeIfAbsent(biomeId, id -> "ukgeo".equals(id.getNamespace()));
    }

    private static boolean isWaterOrWetland(String biome) {
        return "coastal_ocean".equals(biome) || "freshwater".equals(biome) || "wetland".equals(biome);
    }

    private static boolean isWoodland(String biome) {
        return "broadleaf_woodland".equals(biome) || "conifer_woodland".equals(biome);
    }

    private static boolean isGrassland(String biome) {
        return "improved_grassland".equals(biome)
                || "neutral_grassland".equals(biome)
                || "calcareous_grassland".equals(biome)
                || "acid_grassland".equals(biome);
    }

    private static boolean isLowlandOpen(String biome) {
        return "improved_grassland".equals(biome) || "neutral_grassland".equals(biome) || "calcareous_grassland".equals(biome);
    }

    private static boolean isHeath(String biome) {
        return "heath".equals(biome) || "acid_grassland".equals(biome);
    }

    private static boolean isArableEdge(String biome) {
        return "arable".equals(biome) || "neutral_grassland".equals(biome) || "calcareous_grassland".equals(biome) || "urban".equals(biome);
    }

    private static int positionHash(BlockPos pos, int salt) {
        long value = pos.asLong() ^ (long) salt * 0x9E3779B97F4A7C15L;
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return (int) value;
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    private static void debug(String message, Object... args) {
        if (DEBUG_ANIMAL_SPAWNS) {
            LOGGER.info(message, args);
        }
    }

    private static void recordSpawnCheck() {
        if (!DEBUG_PERF) {
            return;
        }
        SPAWN_CHECKS.increment();
        long checks = SPAWN_CHECKS.sum();
        if (checks > 0 && checks % 10_000 == 0) {
            LOGGER.info("UKGeo Animals perf spawnChecks={} blockedSpawns={} biomeCacheSize={}", checks, BLOCKED_SPAWNS.sum(), UKGEO_BIOME_CACHE.size());
        }
    }

    private static AnimalHabitatRuntime animalHabitatRuntime() {
        AnimalHabitatRuntime runtime = ANIMAL_HABITAT_RUNTIME;
        if (runtime != null) {
            return runtime;
        }
        synchronized (ANIMAL_HABITAT_LOCK) {
            runtime = ANIMAL_HABITAT_RUNTIME;
            if (runtime == null) {
                runtime = loadAnimalHabitatRuntime();
                ANIMAL_HABITAT_RUNTIME = runtime;
            }
        }
        return runtime;
    }

    private static AnimalHabitatRuntime loadAnimalHabitatRuntime() {
        Path gameRoot = Path.of(".").toAbsolutePath().normalize();
        Path dataRoot = resolveUkGeoDataRoot(gameRoot);
        Path manifestPath = dataRoot.resolve("manifest.json");
        if (!Files.exists(manifestPath)) {
            LOGGER.warn("UKGeo Animals did not find {}", manifestPath);
            return AnimalHabitatRuntime.disabled();
        }
        try (Reader reader = Files.newBufferedReader(manifestPath)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject animalHabitats = json.getAsJsonObject("animal_habitats");
            if (animalHabitats == null) {
                LOGGER.warn("UKGeo Animals did not find animal_habitats in {}", manifestPath);
                return AnimalHabitatRuntime.disabled();
            }
            JsonObject world = json.getAsJsonObject("world");
            int tileSize = json.get("tile_size").getAsInt();
            int minecraftMinX = world.get("minecraft_min_x").getAsInt();
            int minecraftMinZ = world.get("minecraft_min_z").getAsInt();
            int paddedWidth = world.get("padded_width").getAsInt();
            int paddedDepth = world.get("padded_depth").getAsInt();
            JsonObject entities = animalHabitats.getAsJsonObject("entities");
            if (entities == null || entities.entrySet().isEmpty()) {
                LOGGER.warn("UKGeo Animals found no animal habitat entities in {}", manifestPath);
                return AnimalHabitatRuntime.disabled();
            }
            Map<ResourceLocation, HabitatLayer> layers = new ConcurrentHashMap<>();
            for (Map.Entry<String, JsonElement> entry : entities.entrySet()) {
                JsonObject layer = entry.getValue().getAsJsonObject();
                ResourceLocation entityId = ResourceLocation.parse(entry.getKey());
                String layerPath = layer.get("path").getAsString();
                String extension = layer.has("extension") ? layer.get("extension").getAsString() : ".u8";
                int cellBlocks = layer.has("cell_blocks") ? Math.max(1, layer.get("cell_blocks").getAsInt()) : 1;
                String storage = layer.has("storage") ? layer.get("storage").getAsString() : "tiles";
                String regionPath = layer.has("region_path") ? layer.get("region_path").getAsString() : layerPath + "/regions";
                String regionExtension = layer.has("region_extension") ? layer.get("region_extension").getAsString() : ".u8rg";
                int regionTiles = layer.has("region_tiles") ? Math.max(1, layer.get("region_tiles").getAsInt()) : 8;
                int missingTile = layer.has("missing_tile") ? layer.get("missing_tile").getAsInt() : 0;
                layers.put(entityId, new HabitatLayer(dataRoot, layerPath, extension, storage, regionPath, regionExtension, regionTiles, missingTile, tileSize, minecraftMinX, minecraftMinZ, paddedWidth, paddedDepth, cellBlocks));
            }
            LOGGER.info("UKGeo Animals loaded {} SVG habitat masks from {}", layers.size(), manifestPath);
            return new AnimalHabitatRuntime(layers);
        } catch (Exception ex) {
            LOGGER.warn("UKGeo Animals could not load SVG habitat masks from {}: {}", manifestPath, ex.getMessage());
            return AnimalHabitatRuntime.disabled();
        }
    }

    private static Path resolveUkGeoDataRoot(Path gameRoot) {
        try {
            Class<?> configClass = Class.forName("com.ukgeo.worldgen.UkGeoConfig");
            Object resolved = configClass.getMethod("dataRoot", Path.class).invoke(null, gameRoot);
            if (resolved instanceof Path path) {
                return path.toAbsolutePath().normalize();
            }
        } catch (ReflectiveOperationException ex) {
            debug("Could not resolve ukgeo data root reflectively: %s", ex.getMessage());
        }
        return gameRoot.resolve("uk_world_data").toAbsolutePath().normalize();
    }

    private record AnimalHabitatRuntime(Map<ResourceLocation, HabitatLayer> layers) {
        static AnimalHabitatRuntime disabled() {
            return new AnimalHabitatRuntime(Map.of());
        }

        boolean enabled() {
            return !layers.isEmpty();
        }
    }

    private static final class HabitatLayer {
        private final Path root;
        private final String path;
        private final String extension;
        private final String storage;
        private final String regionPath;
        private final String regionExtension;
        private final int regionTiles;
        private final int missingTile;
        private final int tileSize;
        private final int minecraftMinX;
        private final int minecraftMinZ;
        private final int paddedWidth;
        private final int paddedDepth;
        private final int cellBlocks;
        private final Map<Long, byte[]> cache = Collections.synchronizedMap(new LinkedHashMap<>(32, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
                return size() > 32;
            }
        });

        private HabitatLayer(Path root, String path, String extension, String storage, String regionPath, String regionExtension, int regionTiles, int missingTile, int tileSize, int minecraftMinX, int minecraftMinZ, int paddedWidth, int paddedDepth, int cellBlocks) {
            this.root = root;
            this.path = path;
            this.extension = extension == null || extension.isBlank() ? ".u8" : extension;
            this.storage = storage == null || storage.isBlank() ? "tiles" : storage;
            this.regionPath = regionPath == null || regionPath.isBlank() ? path + "/regions" : regionPath;
            this.regionExtension = regionExtension == null || regionExtension.isBlank() ? ".u8rg" : regionExtension;
            this.regionTiles = Math.max(1, regionTiles);
            this.missingTile = missingTile;
            this.tileSize = tileSize;
            this.minecraftMinX = minecraftMinX;
            this.minecraftMinZ = minecraftMinZ;
            this.paddedWidth = paddedWidth;
            this.paddedDepth = paddedDepth;
            this.cellBlocks = Math.max(1, cellBlocks);
        }

        private int sampleOrDefault(int worldX, int worldZ, int defaultValue) {
            int relativeX = worldX - minecraftMinX;
            int relativeZ = worldZ - minecraftMinZ;
            if (relativeX < 0 || relativeZ < 0 || relativeX >= paddedWidth || relativeZ >= paddedDepth) {
                return defaultValue;
            }
            int blocksPerTile = tileSize * cellBlocks;
            int tileX = relativeX / blocksPerTile;
            int tileZ = relativeZ / blocksPerTile;
            int localX = Math.floorMod(relativeX, blocksPerTile) / cellBlocks;
            int localZ = Math.floorMod(relativeZ, blocksPerTile) / cellBlocks;
            try {
                byte[] tile = loadTile(tileX, tileZ);
                return Byte.toUnsignedInt(tile[localZ * tileSize + localX]);
            } catch (IOException ex) {
                LOGGER.warn("UKGeo Animals could not read habitat tile {} {}_{}: {}", path, tileX, tileZ, ex.getMessage());
                return defaultValue;
            }
        }

        private byte[] loadTile(int tileX, int tileZ) throws IOException {
            long key = ((long) tileX << 32) ^ (tileZ & 0xffffffffL);
            byte[] cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            if ("regions".equalsIgnoreCase(storage)) {
                byte[] data = readPackedTile(root, regionPath, regionExtension, regionTiles, tileSize, tileX, tileZ, missingTile);
                cache.put(key, data);
                return data;
            }
            Path tilePath = root.resolve(path).resolve("%03d_%03d%s".formatted(tileX, tileZ, extension));
            byte[] data = readTileBytes(tilePath, tileSize * tileSize);
            cache.put(key, data);
            return data;
        }

        private static byte[] readTileBytes(Path tilePath, int expectedSize) throws IOException {
            tilePath = resolveTilePath(tilePath);
            if (!Files.exists(tilePath)) {
                return new byte[expectedSize];
            }
            try (InputStream raw = Files.newInputStream(tilePath);
                 InputStream in = tilePath.getFileName().toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw) {
                byte[] data = in.readNBytes(expectedSize);
                if (data.length < expectedSize) {
                    byte[] padded = new byte[expectedSize];
                    System.arraycopy(data, 0, padded, 0, data.length);
                    return padded;
                }
                return data;
            }
        }

        private static Path resolveTilePath(Path path) {
            if (Files.exists(path)) {
                return path;
            }
            String fileName = path.getFileName().toString();
            Path parent = path.getParent();
            if (fileName.endsWith(".gz")) {
                Path raw = parent == null ? Path.of(fileName.substring(0, fileName.length() - 3)) : parent.resolve(fileName.substring(0, fileName.length() - 3));
                return Files.exists(raw) ? raw : path;
            }
            Path gzip = parent == null ? Path.of(fileName + ".gz") : parent.resolve(fileName + ".gz");
            return Files.exists(gzip) ? gzip : path;
        }

        private static byte[] readPackedTile(Path root, String regionPath, String regionExtension, int regionTiles, int tileSize, int tileX, int tileZ, int missingTile) throws IOException {
            int regionX = Math.floorDiv(tileX, regionTiles);
            int regionZ = Math.floorDiv(tileZ, regionTiles);
            int localX = Math.floorMod(tileX, regionTiles);
            int localZ = Math.floorMod(tileZ, regionTiles);
            int index = localZ * regionTiles + localX;
            int tileBytes = tileSize * tileSize;
            Path path = root.resolve(regionPath).resolve("%03d_%03d%s".formatted(regionX, regionZ, regionExtension));
            if (!Files.exists(path)) {
                return defaultTile(tileBytes, missingTile);
            }
            byte[] file = Files.readAllBytes(path);
            if (file.length < 28) {
                throw new IOException(path + " is too small to be a UKGeo packed region");
            }
            ByteBuffer buffer = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
            if (buffer.get() != 'U' || buffer.get() != 'K' || buffer.get() != 'R' || buffer.get() != 'G') {
                throw new IOException(path + " is not a UKGeo packed region");
            }
            int version = buffer.getInt();
            int storedTileSize = buffer.getInt();
            int storedRegionTiles = buffer.getInt();
            int storedTileBytes = buffer.getInt();
            int storedDefault = buffer.getInt();
            int entryCount = buffer.getInt();
            if (version != 1 || storedTileSize != tileSize || storedRegionTiles != regionTiles || storedTileBytes != tileBytes) {
                throw new IOException(path + " packed region metadata does not match manifest");
            }
            if (index >= entryCount) {
                return defaultTile(tileBytes, storedDefault);
            }
            int entryOffset = 28 + index * 12;
            if (file.length < entryOffset + 12) {
                throw new IOException(path + " packed region entry table is truncated");
            }
            buffer.position(entryOffset);
            long payloadOffset = buffer.getLong();
            int payloadSize = buffer.getInt();
            if (payloadOffset == 0L || payloadSize == 0) {
                return defaultTile(tileBytes, storedDefault);
            }
            if (payloadSize <= 0 || payloadOffset < 0 || payloadOffset + payloadSize > file.length) {
                throw new IOException(path + " packed region payload is invalid");
            }
            byte[] payload = Arrays.copyOfRange(file, (int) payloadOffset, (int) payloadOffset + payloadSize);
            if (payloadSize == tileBytes) {
                return payload;
            }
            return inflate(path, payload, tileBytes);
        }

        private static byte[] defaultTile(int tileBytes, int value) {
            byte[] data = new byte[tileBytes];
            Arrays.fill(data, (byte) (value & 0xff));
            return data;
        }

        private static byte[] inflate(Path path, byte[] payload, int expectedSize) throws IOException {
            Inflater inflater = new Inflater();
            try {
                inflater.setInput(payload);
                byte[] data = new byte[expectedSize];
                int length = inflater.inflate(data);
                if (length != expectedSize || !inflater.finished()) {
                    throw new IOException(path + " packed region payload decompressed to " + length + " bytes, expected " + expectedSize);
                }
                return data;
            } catch (DataFormatException ex) {
                throw new IOException(path + " packed region payload is not valid deflate data", ex);
            } finally {
                inflater.end();
            }
        }
    }

    private record SpawnDecision(boolean allowed, String reason) {
        static SpawnDecision allow() {
            return new SpawnDecision(true, "allow");
        }

        static SpawnDecision block(String reason) {
            return new SpawnDecision(false, reason);
        }
    }
}
