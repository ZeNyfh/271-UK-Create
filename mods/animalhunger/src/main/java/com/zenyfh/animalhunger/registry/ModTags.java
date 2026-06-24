package com.zenyfh.animalhunger.registry;

import com.zenyfh.animalhunger.AnimalHunger;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

public final class ModTags {
    public static final TagKey<Item> ANY_ANIMAL_FOOD = item("any_animal_food");
    public static final TagKey<Block> GRAZING_BLOCKS = block("grazing_blocks");

    private ModTags() {
    }

    public static TagKey<Item> food(String path) {
        return item("foods/" + path);
    }

    private static TagKey<Item> item(String path) {
        return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(AnimalHunger.MOD_ID, path));
    }

    private static TagKey<Block> block(String path) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(AnimalHunger.MOD_ID, path));
    }
}
