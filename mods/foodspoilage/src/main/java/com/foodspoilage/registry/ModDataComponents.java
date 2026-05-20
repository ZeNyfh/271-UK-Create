package com.foodspoilage.registry;

import com.foodspoilage.FoodSpoilage;
import com.foodspoilage.spoilage.FoodStackData;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModDataComponents {
    public static final DeferredRegister.DataComponents REGISTRAR = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, FoodSpoilage.MOD_ID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<FoodStackData>> FOOD_STACK_DATA =
        REGISTRAR.registerComponentType("food_stack_data", builder -> builder
            .persistent(FoodStackData.CODEC)
            .cacheEncoding()
        );

    private ModDataComponents() {
    }
}
