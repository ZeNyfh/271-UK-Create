package git.zenyfh.vanilla_adjustments;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Runtime-only processor that remaps structure-placed craftable copper to decorative copper.
 * Not registered as a datapack processor type; injected via mixin when the config flag is enabled.
 */
public final class DecorativeCopperStructureProcessor extends StructureProcessor {
    public static final DecorativeCopperStructureProcessor INSTANCE = new DecorativeCopperStructureProcessor();

    private DecorativeCopperStructureProcessor() {
    }

    @Nullable
    @Override
    public StructureTemplate.StructureBlockInfo processBlock(
            LevelReader level,
            BlockPos offset,
            BlockPos pos,
            StructureTemplate.StructureBlockInfo blockInfo,
            StructureTemplate.StructureBlockInfo relativeBlockInfo,
            StructurePlaceSettings settings) {
        BlockState remapped = DecorativeCopperRemapper.remap(relativeBlockInfo.state());
        if (remapped == relativeBlockInfo.state()) {
            return relativeBlockInfo;
        }
        return new StructureTemplate.StructureBlockInfo(relativeBlockInfo.pos(), remapped, relativeBlockInfo.nbt());
    }

    @Override
    protected StructureProcessorType<?> getType() {
        // Runtime injection only; never serialized through datapack processor lists.
        return StructureProcessorType.NOP;
    }
}
