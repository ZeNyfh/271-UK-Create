package git.zenyfh.vanilla_adjustments;

import java.util.IdentityHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Maps vanilla craftable copper blocks used by structures onto decorative equivalents
 * that look identical but are not usable as copper crafting ingredients.
 * copper_dispenser is intentionally omitted so trial-chamber dispensers stay functional.
 */
public final class DecorativeCopperRemapper {
    @Nullable
    private static Map<Block, Block> vanillaToDecorative;

    private DecorativeCopperRemapper() {
    }

    @Nullable
    public static Block remap(Block block) {
        return mapping().get(block);
    }

    public static BlockState remap(BlockState state) {
        Block decorative = mapping().get(state.getBlock());
        if (decorative == null) {
            return state;
        }
        return decorative.withPropertiesOf(state);
    }

    public static boolean isRemappable(Block block) {
        return mapping().containsKey(block);
    }

    private static Map<Block, Block> mapping() {
        Map<Block, Block> map = vanillaToDecorative;
        if (map == null) {
            map = buildMapping();
            vanillaToDecorative = map;
        }
        return map;
    }

    private static Map<Block, Block> buildMapping() {
        Map<Block, Block> map = new IdentityHashMap<>();
        map.put(Blocks.COPPER_BLOCK, DecorativeCopperBlocks.DECORATIVE_COPPER_BLOCK.get());
        map.put(Blocks.WAXED_COPPER_BLOCK, DecorativeCopperBlocks.DECORATIVE_WAXED_COPPER_BLOCK.get());
        map.put(Blocks.WAXED_OXIDIZED_COPPER, DecorativeCopperBlocks.DECORATIVE_WAXED_OXIDIZED_COPPER.get());
        map.put(Blocks.WAXED_CUT_COPPER, DecorativeCopperBlocks.DECORATIVE_WAXED_CUT_COPPER.get());
        map.put(Blocks.WAXED_OXIDIZED_CUT_COPPER, DecorativeCopperBlocks.DECORATIVE_WAXED_OXIDIZED_CUT_COPPER.get());
        map.put(Blocks.OXIDIZED_CUT_COPPER, DecorativeCopperBlocks.DECORATIVE_OXIDIZED_CUT_COPPER.get());
        map.put(Blocks.WAXED_CUT_COPPER_STAIRS, DecorativeCopperBlocks.DECORATIVE_WAXED_CUT_COPPER_STAIRS.get());
        map.put(Blocks.WAXED_OXIDIZED_CUT_COPPER_STAIRS, DecorativeCopperBlocks.DECORATIVE_WAXED_OXIDIZED_CUT_COPPER_STAIRS.get());
        map.put(Blocks.WAXED_CUT_COPPER_SLAB, DecorativeCopperBlocks.DECORATIVE_WAXED_CUT_COPPER_SLAB.get());
        map.put(Blocks.WAXED_OXIDIZED_CUT_COPPER_SLAB, DecorativeCopperBlocks.DECORATIVE_WAXED_OXIDIZED_CUT_COPPER_SLAB.get());
        map.put(Blocks.WAXED_CHISELED_COPPER, DecorativeCopperBlocks.DECORATIVE_WAXED_CHISELED_COPPER.get());
        map.put(Blocks.WAXED_OXIDIZED_CHISELED_COPPER, DecorativeCopperBlocks.DECORATIVE_WAXED_OXIDIZED_CHISELED_COPPER.get());
        map.put(Blocks.WAXED_COPPER_GRATE, DecorativeCopperBlocks.DECORATIVE_WAXED_COPPER_GRATE.get());
        map.put(Blocks.WAXED_OXIDIZED_COPPER_GRATE, DecorativeCopperBlocks.DECORATIVE_WAXED_OXIDIZED_COPPER_GRATE.get());
        map.put(Blocks.WAXED_COPPER_BULB, DecorativeCopperBlocks.DECORATIVE_WAXED_COPPER_BULB.get());
        map.put(Blocks.WAXED_COPPER_DOOR, DecorativeCopperBlocks.DECORATIVE_WAXED_COPPER_DOOR.get());
        map.put(Blocks.WAXED_OXIDIZED_COPPER_DOOR, DecorativeCopperBlocks.DECORATIVE_WAXED_OXIDIZED_COPPER_DOOR.get());
        map.put(Blocks.WAXED_OXIDIZED_COPPER_TRAPDOOR, DecorativeCopperBlocks.DECORATIVE_WAXED_OXIDIZED_COPPER_TRAPDOOR.get());
        map.put(Blocks.OXIDIZED_COPPER_TRAPDOOR, DecorativeCopperBlocks.DECORATIVE_OXIDIZED_COPPER_TRAPDOOR.get());
        return Map.copyOf(map);
    }
}
