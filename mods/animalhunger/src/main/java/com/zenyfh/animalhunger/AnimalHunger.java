package com.zenyfh.animalhunger;

import com.zenyfh.animalhunger.config.AnimalHungerConfig;
import com.zenyfh.animalhunger.hunger.AnimalHungerEvents;
import com.zenyfh.animalhunger.registry.ModBlockEntities;
import com.zenyfh.animalhunger.registry.ModBlocks;
import com.zenyfh.animalhunger.registry.ModItems;
import com.zenyfh.animalhunger.registry.ModMenuTypes;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(AnimalHunger.MOD_ID)
public final class AnimalHunger {
    public static final String MOD_ID = "animalhunger";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public AnimalHunger(IEventBus modBus, ModContainer modContainer) {
        ModBlocks.REGISTRAR.register(modBus);
        ModItems.REGISTRAR.register(modBus);
        ModBlockEntities.REGISTRAR.register(modBus);
        ModMenuTypes.REGISTRAR.register(modBus);
        modContainer.registerConfig(ModConfig.Type.COMMON, AnimalHungerConfig.SPEC);
        modBus.addListener(AnimalHunger::buildCreativeTabContents);
        NeoForge.EVENT_BUS.register(new AnimalHungerEvents());
    }

    private static void buildCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModItems.TROUGH.get());
        }
    }
}
