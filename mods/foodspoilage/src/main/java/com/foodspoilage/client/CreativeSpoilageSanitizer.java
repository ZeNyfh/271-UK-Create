package com.foodspoilage.client;

import com.foodspoilage.FoodSpoilage;
import com.foodspoilage.registry.ModDataComponents;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = FoodSpoilage.MOD_ID, value = Dist.CLIENT)
public final class CreativeSpoilageSanitizer {
    private CreativeSpoilageSanitizer() {
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        Screen screen = event.getNewScreen();
        if (screen == null) {
            return;
        }
        if (!(screen instanceof CreativeModeInventoryScreen)
                && !screen.getClass().getName().contains("CreativeModeInventory")) {
            return;
        }

        for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
            sanitizeStacks(tab.getDisplayItems());
            sanitizeStacks(tab.getSearchTabDisplayItems());
        }

        if (screen instanceof CreativeModeInventoryScreen creative) {
            sanitizeStacks(creative.getMenu().items);
        }
    }

    private static void sanitizeStacks(Iterable<ItemStack> stacks) {
        if (stacks == null) {
            return;
        }
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty() && stack.has(ModDataComponents.FOOD_STACK_DATA)) {
                stack.remove(ModDataComponents.FOOD_STACK_DATA);
            }
        }
    }
}
