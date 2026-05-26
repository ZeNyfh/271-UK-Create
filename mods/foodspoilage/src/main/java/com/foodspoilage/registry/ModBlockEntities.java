package com.foodspoilage.registry;

import com.foodspoilage.FoodSpoilage;
import com.foodspoilage.world.IceboxBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> REGISTRAR = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, FoodSpoilage.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<IceboxBlockEntity>> ICEBOX =
        REGISTRAR.register("icebox", () -> BlockEntityType.Builder.of(
            IceboxBlockEntity::new,
            ModBlocks.SIMPLE_ICEBOX.get(),
            ModBlocks.ADVANCED_ICEBOX.get()
        ).build(null));

    private ModBlockEntities() {
    }
}
