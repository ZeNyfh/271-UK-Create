package com.foodspoilage.client;

import com.foodspoilage.FoodSpoilage;
import com.foodspoilage.registry.ModMenuTypes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = FoodSpoilage.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class FoodSpoilageClient {
    private FoodSpoilageClient() {
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.SIMPLE_ICEBOX.get(), IceboxScreen::new);
        event.register(ModMenuTypes.ADVANCED_ICEBOX.get(), IceboxScreen::new);
    }
}
