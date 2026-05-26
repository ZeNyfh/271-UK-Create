package com.foodspoilage.world;

import com.foodspoilage.registry.ModBlockEntities;
import com.foodspoilage.registry.ModBlocks;
import com.foodspoilage.spoilage.SpoilageManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class IceboxBlockEntity extends BlockEntity implements WorldlyContainer, MenuProvider {
    public static final int SLOT_LIMIT = 16;

    private final int size;
    private final int[] exposedSlots;
    private NonNullList<ItemStack> items;

    public IceboxBlockEntity(BlockPos pos, BlockState state) {
        this(pos, state, state.is(ModBlocks.ADVANCED_ICEBOX.get()) ? 9 : 4);
    }

    public IceboxBlockEntity(BlockPos pos, BlockState state, int size) {
        super(ModBlockEntities.ICEBOX.get(), pos, state);
        this.size = size;
        this.items = NonNullList.withSize(size, ItemStack.EMPTY);
        this.exposedSlots = new int[size];
        for (int i = 0; i < size; i++) {
            this.exposedSlots[i] = i;
        }
    }

    @Override
    public int getContainerSize() {
        return this.size;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : this.items) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        refreshSlot(slot);
        return this.items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        refreshSlot(slot);
        ItemStack removed = ContainerHelper.removeItem(this.items, slot, amount);
        if (!removed.isEmpty()) {
            this.setChanged();
        }
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        refreshSlot(slot);
        return ContainerHelper.takeItem(this.items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (stack.getCount() > SLOT_LIMIT) {
            stack = stack.copyWithCount(SLOT_LIMIT);
        }
        this.items.set(slot, stack);
        refreshSlot(slot);
        this.setChanged();
    }

    @Override
    public int getMaxStackSize() {
        return SLOT_LIMIT;
    }

    @Override
    public int getMaxStackSize(ItemStack stack) {
        return SLOT_LIMIT;
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        refreshContents();
        this.items.clear();
        this.setChanged();
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return this.exposedSlots;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction direction) {
        return this.canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return true;
    }

    @Override
    public Component getDisplayName() {
        refreshContents();
        return IceboxBlock.defaultName(this.getBlockState());
    }

    @Override
    @Nullable
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        refreshContents();
        return IceboxMenu.server(containerId, playerInventory, this, ContainerLevelAccess.create(player.level(), this.worldPosition));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        refreshContents();
        ContainerHelper.saveAllItems(tag, this.items, registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.items = NonNullList.withSize(this.size, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, this.items, registries);
        refreshContents();
    }

    private double preservationMultiplier() {
        return this.size >= 9 ? 3.0D : 2.0D;
    }

    private void refreshSlot(int slot) {
        if (slot >= 0 && slot < this.items.size()) {
            SpoilageManager.refreshPreserved(this.items.get(slot), preservationMultiplier());
        }
    }

    private void refreshContents() {
        double multiplier = preservationMultiplier();
        for (ItemStack stack : this.items) {
            SpoilageManager.refreshPreserved(stack, multiplier);
        }
    }
}
