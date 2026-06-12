package com.ukgeoanimals;

import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
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
    private static final ResourceLocation BLUNDERBUSS = ResourceLocation.fromNamespaceAndPath("wildernature", "blunderbuss");
    private static final Set<String> UNWANTED = Set.of(
        "red_wolf",
        "raccoon",
        "penguin",
        "turkey",
        "pelican",
        "flamingo",
        "cassowary"
    );
    private static final Set<String> FILTERED = Set.of(
        "deer",
        "bison",
        "boar",
        "dog",
        "hedgehog",
        "minisheep",
        "owl",
        "squirrel"
    );
    private static final Map<String, Integer> RARENESS = Map.of(
        "bison", 16,
        "dog", 8,
        "owl", 4
    );

    public UkGeoAnimals(IEventBus modBus, ModContainer modContainer) {
        modBus.addListener(UkGeoAnimals::removeBlunderbussFromCreativeTabs);
        NeoForge.EVENT_BUS.register(this);
        LOGGER.info("UKGeo Animals loaded");
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
        if (!"wildernature".equals(entityId.getNamespace())) {
            return;
        }
        String animal = entityId.getPath();
        if (!isNaturalSpawn(event.getSpawnType())) {
            return;
        }

        BlockPos pos = event.getPos();
        ResourceLocation biomeId = event.getLevel().getBiome(pos).unwrapKey()
            .map(ResourceKey::location)
            .orElse(null);
        if (biomeId == null || !"ukgeo".equals(biomeId.getNamespace())) {
            return;
        }

        if (UNWANTED.contains(animal)) {
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
            return;
        }
        if (!FILTERED.contains(animal)) {
            return;
        }
        if (!isHabitatAllowed(animal, biomeId.getPath(), pos.getY())) {
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
            return;
        }
        int rarity = RARENESS.getOrDefault(animal, 1);
        if (rarity > 1 && Math.floorMod(positionHash(pos, animal), rarity) != 0) {
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }

    private static boolean isNaturalSpawn(MobSpawnType spawnType) {
        return spawnType == MobSpawnType.NATURAL || spawnType == MobSpawnType.CHUNK_GENERATION;
    }

    private static boolean isHabitatAllowed(String animal, String biome, int y) {
        if (isWaterOrCoast(biome)) {
            return false;
        }
        return switch (animal) {
            case "deer" -> y <= 265 && (isWoodland(biome) || isGrassland(biome) || isHeath(biome));
            case "boar" -> y <= 245 && (isWoodland(biome) || "arable".equals(biome) || isHeath(biome));
            case "hedgehog" -> y <= 180 && ("arable".equals(biome) || "urban".equals(biome) || isLowlandGrassland(biome) || "broadleaf_woodland".equals(biome));
            case "squirrel" -> y <= 230 && (isWoodland(biome) || "urban".equals(biome));
            case "owl" -> y <= 260 && (isWoodland(biome) || "arable".equals(biome) || "neutral_grassland".equals(biome) || isHeath(biome));
            case "dog" -> y <= 160 && ("arable".equals(biome) || "urban".equals(biome) || isLowlandGrassland(biome));
            case "minisheep" -> y <= 285 && (isGrassland(biome) || isHeath(biome) || "rocky".equals(biome));
            case "bison" -> y <= 210 && ("improved_grassland".equals(biome) || "neutral_grassland".equals(biome) || isHeath(biome));
            default -> true;
        };
    }

    private static boolean isWaterOrCoast(String biome) {
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

    private static boolean isLowlandGrassland(String biome) {
        return "improved_grassland".equals(biome) || "neutral_grassland".equals(biome) || "calcareous_grassland".equals(biome);
    }

    private static boolean isHeath(String biome) {
        return "heath".equals(biome) || "acid_grassland".equals(biome);
    }

    private static int positionHash(BlockPos pos, String salt) {
        long value = pos.asLong() ^ salt.hashCode() * 0x9E3779B97F4A7C15L;
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return (int) value;
    }
}
