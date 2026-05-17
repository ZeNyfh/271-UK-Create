package com.ukgeo.worldgen;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(UkGeoMod.MOD_ID)
public final class UkGeoMod {
    public static final String MOD_ID = "ukgeo";
    public static final Logger LOGGER = LoggerFactory.getLogger("ukgeo");
    public static final ResourceLocation HEIGHTMAP_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "heightmap");

    public UkGeoMod(IEventBus modBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, UkGeoConfig.SPEC);
        modBus.addListener(this::register);
        NeoForge.EVENT_BUS.addListener(UkGeoCommands::register);
    }

    private void register(RegisterEvent event) {
        event.register(Registries.CHUNK_GENERATOR, HEIGHTMAP_ID, () -> (MapCodec<? extends ChunkGenerator>) UkGeoChunkGenerator.CODEC);
    }
}
