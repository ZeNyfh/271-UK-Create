package com.foodspoilage.registry;

import com.foodspoilage.FoodSpoilage;
import com.foodspoilage.world.IceboxMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenuTypes {
    public static final DeferredRegister<MenuType<?>> REGISTRAR = DeferredRegister.create(Registries.MENU, FoodSpoilage.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<IceboxMenu>> SIMPLE_ICEBOX =
        REGISTRAR.register("simple_icebox", () -> new MenuType<>(IceboxMenu::simpleClient, FeatureFlags.VANILLA_SET));

    public static final DeferredHolder<MenuType<?>, MenuType<IceboxMenu>> ADVANCED_ICEBOX =
        REGISTRAR.register("advanced_icebox", () -> new MenuType<>(IceboxMenu::advancedClient, FeatureFlags.VANILLA_SET));

    private ModMenuTypes() {
    }
}
