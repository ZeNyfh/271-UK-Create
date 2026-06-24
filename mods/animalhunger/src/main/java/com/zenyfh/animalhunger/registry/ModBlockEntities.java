package com.zenyfh.animalhunger.registry;

import com.zenyfh.animalhunger.AnimalHunger;
import com.zenyfh.animalhunger.world.TroughBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> REGISTRAR = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, AnimalHunger.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TroughBlockEntity>> TROUGH =
        REGISTRAR.register("trough", () -> BlockEntityType.Builder.of(TroughBlockEntity::new, ModBlocks.TROUGH.get()).build(null));

    private ModBlockEntities() {
    }
}
