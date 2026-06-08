package git.zenyfh.vanilla_adjustments;

import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

public final class DeepslateFluidGeneratorHandler {
    private static final Set<String> CREATE_DECORATIVE_STONE_OUTPUTS = Set.of(
            "limestone",
            "scoria",
            "asurine",
            "crimsite",
            "ochrum",
            "veridium",
            "andesite",
            "diorite",
            "granite",
            "tuff",
            "calcite",
            "smooth_basalt"
    );

    @SubscribeEvent
    public void onFluidPlaceBlock(BlockEvent.FluidPlaceBlockEvent event) {
        if (!VanillaAdjustConfig.DEEPSLATE_FLUID_GENERATORS.get()) {
            return;
        }

        BlockState newState = event.getNewState();
        BlockState replacement = remapGeneratedStoneLikeBlock(newState);
        if (!replacement.equals(newState)) {
            event.setNewState(replacement);
        }
    }

    static BlockState remapGeneratedStoneLikeBlock(BlockState original) {
        if (VanillaAdjustConfig.REPLACE_COBBLESTONE_GENERATORS_WITH_COBBLED_DEEPSLATE.get()
                && original.is(Blocks.COBBLESTONE)) {
            return Blocks.COBBLED_DEEPSLATE.defaultBlockState();
        }
        if (VanillaAdjustConfig.REPLACE_STONE_GENERATORS_WITH_DEEPSLATE.get()
                && original.is(Blocks.STONE)) {
            return Blocks.DEEPSLATE.defaultBlockState();
        }
        if (VanillaAdjustConfig.REPLACE_BASALT_GENERATORS_WITH_DEEPSLATE.get()
                && original.is(Blocks.BASALT)) {
            return Blocks.DEEPSLATE.defaultBlockState();
        }
        if (VanillaAdjustConfig.REPLACE_CREATE_DECORATIVE_STONE_GENERATORS_WITH_DEEPSLATE.get()
                && isCreateStyleDecorativeStoneOutput(original)) {
            return Blocks.DEEPSLATE.defaultBlockState();
        }
        return original;
    }

    private static boolean isCreateStyleDecorativeStoneOutput(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if ("create".equals(id.getNamespace())) {
            return CREATE_DECORATIVE_STONE_OUTPUTS.contains(id.getPath());
        }
        return "minecraft".equals(id.getNamespace()) && CREATE_DECORATIVE_STONE_OUTPUTS.contains(id.getPath());
    }
}
