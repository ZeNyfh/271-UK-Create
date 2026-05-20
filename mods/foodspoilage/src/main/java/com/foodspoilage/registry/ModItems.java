package com.foodspoilage.registry;

import com.foodspoilage.FoodSpoilage;
import com.foodspoilage.spoilage.SpoiledFoodItem;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items REGISTRAR = DeferredRegister.createItems(FoodSpoilage.MOD_ID);

    private static final FoodProperties SPOILED_FOOD = new FoodProperties(1, 0.1F, false, 1.6F, Optional.empty(), List.of());

    public static final DeferredHolder<Item, Item> SPOILED_RAW_FOOD = REGISTRAR.register("spoiled_raw_food",
        () -> new SpoiledFoodItem(new Item.Properties().food(SPOILED_FOOD)));

    public static final DeferredHolder<Item, Item> SPOILED_COOKED_FOOD = REGISTRAR.register("spoiled_cooked_food",
        () -> new SpoiledFoodItem(new Item.Properties().food(SPOILED_FOOD)));

    private ModItems() {
    }
}
