package com.foodspoilage.registry;

import com.foodspoilage.FoodSpoilage;
import com.foodspoilage.world.IceboxBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks REGISTRAR = DeferredRegister.createBlocks(FoodSpoilage.MOD_ID);

    public static final DeferredBlock<Block> SIMPLE_ICEBOX = REGISTRAR.registerBlock(
        "simple_icebox",
        properties -> new IceboxBlock(properties, 4),
        iceboxProperties()
    );

    public static final DeferredBlock<Block> ADVANCED_ICEBOX = REGISTRAR.registerBlock(
        "advanced_icebox",
        properties -> new IceboxBlock(properties, 9),
        iceboxProperties()
    );

    private ModBlocks() {
    }

    private static BlockBehaviour.Properties iceboxProperties() {
        return BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_BLUE)
            .strength(2.5F, 6.0F)
            .sound(SoundType.WOOD);
    }
}
