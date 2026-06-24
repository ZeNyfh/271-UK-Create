package com.zenyfh.animalhunger.registry;

import com.zenyfh.animalhunger.AnimalHunger;
import com.zenyfh.animalhunger.world.TroughBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks REGISTRAR = DeferredRegister.createBlocks(AnimalHunger.MOD_ID);

    public static final DeferredBlock<Block> TROUGH = REGISTRAR.registerBlock(
        "trough",
        TroughBlock::new,
        BlockBehaviour.Properties.of()
            .mapColor(MapColor.WOOD)
            .strength(2.0F, 3.0F)
            .sound(SoundType.WOOD)
    );

    private ModBlocks() {
    }
}
