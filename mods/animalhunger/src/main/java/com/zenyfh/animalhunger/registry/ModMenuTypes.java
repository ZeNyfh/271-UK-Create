package com.zenyfh.animalhunger.registry;

import com.zenyfh.animalhunger.AnimalHunger;
import com.zenyfh.animalhunger.world.TroughMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenuTypes {
    public static final DeferredRegister<MenuType<?>> REGISTRAR = DeferredRegister.create(Registries.MENU, AnimalHunger.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<TroughMenu>> TROUGH =
        REGISTRAR.register("trough", () -> new MenuType<>(TroughMenu::client, FeatureFlags.VANILLA_SET));

    private ModMenuTypes() {
    }
}
