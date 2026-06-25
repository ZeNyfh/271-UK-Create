package com.ukgeoanimals;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
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

    private record SpawnDecision(boolean allowed, String reason) {
        static SpawnDecision allow() {
            return new SpawnDecision(true, "allow");
        }

        static SpawnDecision block(String reason) {
            return new SpawnDecision(false, reason);
        }
    }
}
