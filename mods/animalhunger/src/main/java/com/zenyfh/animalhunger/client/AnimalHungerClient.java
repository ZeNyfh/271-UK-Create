package com.zenyfh.animalhunger.client;

import com.zenyfh.animalhunger.AnimalHunger;
import com.zenyfh.animalhunger.registry.ModMenuTypes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = AnimalHunger.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class AnimalHungerClient {
    private AnimalHungerClient() {
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.TROUGH.get(), TroughScreen::new);
    }
}
