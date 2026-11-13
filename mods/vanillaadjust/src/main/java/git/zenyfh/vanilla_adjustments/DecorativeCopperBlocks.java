package git.zenyfh.vanilla_adjustments;

import java.util.function.Supplier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WaterloggedTransparentBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class DecorativeCopperBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Vanilla_adjustments.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Vanilla_adjustments.MODID);

    public static final DeferredBlock<Block> DECORATIVE_COPPER_BLOCK = registerSolid(
            "decorative_copper_block", () -> new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.COPPER_BLOCK)));
    public static final DeferredBlock<Block> DECORATIVE_WAXED_COPPER_BLOCK = registerSolid(
            "decorative_waxed_copper_block", () -> new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.WAXED_COPPER_BLOCK)));
    public static final DeferredBlock<Block> DECORATIVE_WAXED_OXIDIZED_COPPER = registerSolid(
            "decorative_waxed_oxidized_copper", () -> new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.WAXED_OXIDIZED_COPPER)));

    public static final DeferredBlock<Block> DECORATIVE_WAXED_CUT_COPPER = registerSolid(
            "decorative_waxed_cut_copper", () -> new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.WAXED_CUT_COPPER)));
    public static final DeferredBlock<Block> DECORATIVE_WAXED_OXIDIZED_CUT_COPPER = registerSolid(
            "decorative_waxed_oxidized_cut_copper",
            () -> new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.WAXED_OXIDIZED_CUT_COPPER)));
    public static final DeferredBlock<Block> DECORATIVE_OXIDIZED_CUT_COPPER = registerSolid(
            "decorative_oxidized_cut_copper", () -> new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.OXIDIZED_CUT_COPPER)));

    public static final DeferredBlock<StairBlock> DECORATIVE_WAXED_CUT_COPPER_STAIRS = registerStairs(
            "decorative_waxed_cut_copper_stairs",
            DECORATIVE_WAXED_CUT_COPPER,
            Blocks.WAXED_CUT_COPPER_STAIRS);
    public static final DeferredBlock<StairBlock> DECORATIVE_WAXED_OXIDIZED_CUT_COPPER_STAIRS = registerStairs(
            "decorative_waxed_oxidized_cut_copper_stairs",
            DECORATIVE_WAXED_OXIDIZED_CUT_COPPER,
            Blocks.WAXED_OXIDIZED_CUT_COPPER_STAIRS);

    public static final DeferredBlock<SlabBlock> DECORATIVE_WAXED_CUT_COPPER_SLAB = registerSlab(
            "decorative_waxed_cut_copper_slab", Blocks.WAXED_CUT_COPPER_SLAB);
    public static final DeferredBlock<SlabBlock> DECORATIVE_WAXED_OXIDIZED_CUT_COPPER_SLAB = registerSlab(
            "decorative_waxed_oxidized_cut_copper_slab", Blocks.WAXED_OXIDIZED_CUT_COPPER_SLAB);

    public static final DeferredBlock<Block> DECORATIVE_WAXED_CHISELED_COPPER = registerSolid(
            "decorative_waxed_chiseled_copper", () -> new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.WAXED_CHISELED_COPPER)));
    public static final DeferredBlock<Block> DECORATIVE_WAXED_OXIDIZED_CHISELED_COPPER = registerSolid(
            "decorative_waxed_oxidized_chiseled_copper",
            () -> new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.WAXED_OXIDIZED_CHISELED_COPPER)));

    public static final DeferredBlock<WaterloggedTransparentBlock> DECORATIVE_WAXED_COPPER_GRATE = registerGrate(
            "decorative_waxed_copper_grate", Blocks.WAXED_COPPER_GRATE);
    public static final DeferredBlock<WaterloggedTransparentBlock> DECORATIVE_WAXED_OXIDIZED_COPPER_GRATE = registerGrate(
            "decorative_waxed_oxidized_copper_grate", Blocks.WAXED_OXIDIZED_COPPER_GRATE);

    public static final DeferredBlock<CopperBulbBlock> DECORATIVE_WAXED_COPPER_BULB = BLOCKS.register(
            "decorative_waxed_copper_bulb",
            () -> new CopperBulbBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.WAXED_COPPER_BULB)));
    public static final DeferredItem<BlockItem> DECORATIVE_WAXED_COPPER_BULB_ITEM =
            ITEMS.registerSimpleBlockItem(DECORATIVE_WAXED_COPPER_BULB);

    public static final DeferredBlock<DoorBlock> DECORATIVE_WAXED_COPPER_DOOR = registerDoor(
            "decorative_waxed_copper_door", Blocks.WAXED_COPPER_DOOR);
    public static final DeferredBlock<DoorBlock> DECORATIVE_WAXED_OXIDIZED_COPPER_DOOR = registerDoor(
            "decorative_waxed_oxidized_copper_door", Blocks.WAXED_OXIDIZED_COPPER_DOOR);

    public static final DeferredBlock<TrapDoorBlock> DECORATIVE_WAXED_OXIDIZED_COPPER_TRAPDOOR = registerTrapdoor(
            "decorative_waxed_oxidized_copper_trapdoor", Blocks.WAXED_OXIDIZED_COPPER_TRAPDOOR);
    public static final DeferredBlock<TrapDoorBlock> DECORATIVE_OXIDIZED_COPPER_TRAPDOOR = registerTrapdoor(
            "decorative_oxidized_copper_trapdoor", Blocks.OXIDIZED_COPPER_TRAPDOOR);

    private DecorativeCopperBlocks() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        modEventBus.addListener(DecorativeCopperBlocks::addCreative);
    }

    private static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() != CreativeModeTabs.BUILDING_BLOCKS) {
            return;
        }
        for (var item : ITEMS.getEntries()) {
            event.accept(item.get());
        }
    }

    private static DeferredBlock<Block> registerSolid(String name, Supplier<Block> supplier) {
        DeferredBlock<Block> block = BLOCKS.register(name, supplier);
        ITEMS.registerSimpleBlockItem(block);
        return block;
    }

    private static DeferredBlock<StairBlock> registerStairs(String name, DeferredBlock<Block> base, Block vanillaStairs) {
        DeferredBlock<StairBlock> block = BLOCKS.register(
                name,
                () -> new StairBlock(base.get().defaultBlockState(), BlockBehaviour.Properties.ofFullCopy(vanillaStairs)));
        ITEMS.registerSimpleBlockItem(block);
        return block;
    }

    private static DeferredBlock<SlabBlock> registerSlab(String name, Block vanillaSlab) {
        DeferredBlock<SlabBlock> block = BLOCKS.register(
                name, () -> new SlabBlock(BlockBehaviour.Properties.ofFullCopy(vanillaSlab)));
        ITEMS.registerSimpleBlockItem(block);
        return block;
    }

    private static DeferredBlock<WaterloggedTransparentBlock> registerGrate(String name, Block vanillaGrate) {
        DeferredBlock<WaterloggedTransparentBlock> block = BLOCKS.register(
                name, () -> new WaterloggedTransparentBlock(BlockBehaviour.Properties.ofFullCopy(vanillaGrate)));
        ITEMS.registerSimpleBlockItem(block);
        return block;
    }

    private static DeferredBlock<DoorBlock> registerDoor(String name, Block vanillaDoor) {
        DeferredBlock<DoorBlock> block = BLOCKS.register(
                name, () -> new DoorBlock(BlockSetType.COPPER, BlockBehaviour.Properties.ofFullCopy(vanillaDoor)));
        ITEMS.register(name, () -> new DoubleHighBlockItem(block.get(), new Item.Properties()));
        return block;
    }

    private static DeferredBlock<TrapDoorBlock> registerTrapdoor(String name, Block vanillaTrapdoor) {
        DeferredBlock<TrapDoorBlock> block = BLOCKS.register(
                name, () -> new TrapDoorBlock(BlockSetType.COPPER, BlockBehaviour.Properties.ofFullCopy(vanillaTrapdoor)));
        ITEMS.registerSimpleBlockItem(block);
        return block;
    }
}
