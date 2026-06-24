package com.zenyfh.animalhunger.registry;

import com.zenyfh.animalhunger.AnimalHunger;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items REGISTRAR = DeferredRegister.createItems(AnimalHunger.MOD_ID);

    public static final DeferredHolder<Item, BlockItem> TROUGH = REGISTRAR.register("trough",
        () -> new BlockItem(ModBlocks.TROUGH.get(), new Item.Properties()));

    private ModItems() {
    }
}
