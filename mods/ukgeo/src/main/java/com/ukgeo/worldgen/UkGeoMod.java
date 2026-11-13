package com.ukgeo.worldgen;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(UkGeoMod.MOD_ID)
public final class UkGeoMod {
    public static final String MOD_ID = "ukgeo";
    public static final Logger LOGGER = LoggerFactory.getLogger("ukgeo");
    public static final ResourceLocation HEIGHTMAP_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "heightmap");
    public static final ResourceLocation VEGETATION_BIOME_SOURCE_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "vegetation");
    private static final String DISTANT_HORIZONS_MOD_ID = "distanthorizons";

    public UkGeoMod(IEventBus modBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, UkGeoConfig.SPEC);
        modBus.addListener(this::register);
        NeoForge.EVENT_BUS.addListener(UkGeoCommands::register);
        NeoForge.EVENT_BUS.addListener(UkGeoOilIntegration::onServerTick);
        NeoForge.EVENT_BUS.addListener(TravelChunkPrefetch::onServerTick);
        NeoForge.EVENT_BUS.addListener(UkGeoMod::onServerTick);
        maybeInitDistantHorizonsLodGenerator();
    }

    /**
     * Soft Distant Horizons hook. {@code UkGeoDhBootstrap} is the only class that imports the DH API
     * and is only loaded after {@link ModList} confirms DH is present.
     */
    private static void maybeInitDistantHorizonsLodGenerator() {
        if (!ModList.get().isLoaded(DISTANT_HORIZONS_MOD_ID)) {
            LOGGER.info("UKGeo DH LOD generator skipped: Distant Horizons not loaded");
            return;
        }
        String flag = System.getProperty("ukgeo.enableDhLodGenerator");
        if (flag != null && !Boolean.parseBoolean(flag)) {
            LOGGER.info("UKGeo DH LOD generator skipped: -Dukgeo.enableDhLodGenerator=false");
            return;
        }
        try {
            com.ukgeo.worldgen.dh.UkGeoDhBootstrap.init();
        } catch (NoClassDefFoundError | ExceptionInInitializerError | RuntimeException ex) {
            LOGGER.warn("UKGeo DH LOD generator skipped: {}", ex.toString());
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        UkGeoChunkGenerator.refreshPlayerChunkSnapshot(event.getServer());
    }

    private void register(RegisterEvent event) {
        event.register(Registries.CHUNK_GENERATOR, HEIGHTMAP_ID, () -> (MapCodec<? extends ChunkGenerator>) UkGeoChunkGenerator.CODEC);
        event.register(Registries.BIOME_SOURCE, VEGETATION_BIOME_SOURCE_ID, () -> (MapCodec<? extends BiomeSource>) UkGeoVegetationBiomeSource.CODEC);
    }
}
