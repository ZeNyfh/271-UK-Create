package com.foodspoilage;

import com.foodspoilage.config.FoodSpoilageConfig;
import com.foodspoilage.event.FoodSpoilageEvents;
import com.foodspoilage.registry.ModDataComponents;
import com.foodspoilage.registry.ModItems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(FoodSpoilage.MOD_ID)
public final class FoodSpoilage {
    public static final String MOD_ID = "foodspoilage";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public FoodSpoilage(IEventBus modBus, ModContainer modContainer) {
        ModDataComponents.REGISTRAR.register(modBus);
        ModItems.REGISTRAR.register(modBus);
        modContainer.registerConfig(ModConfig.Type.COMMON, FoodSpoilageConfig.SPEC);

        FoodSpoilageEvents.register(NeoForge.EVENT_BUS);
    }
}
