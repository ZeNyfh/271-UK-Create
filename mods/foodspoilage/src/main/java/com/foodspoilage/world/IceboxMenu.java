package com.foodspoilage.world;

import com.foodspoilage.registry.ModBlocks;
import com.foodspoilage.registry.ModMenuTypes;
import com.foodspoilage.spoilage.SpoilageManager;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class IceboxMenu extends AbstractContainerMenu {
    public static final int SIMPLE_SLOTS = 4;
    public static final int ADVANCED_SLOTS = 9;

    private final Container container;
    private final ContainerLevelAccess access;
    private final int iceboxSlots;

    public static IceboxMenu simpleClient(int containerId, Inventory inventory) {
        return new IceboxMenu(ModMenuTypes.SIMPLE_ICEBOX.get(), containerId, inventory, new SimpleContainer(SIMPLE_SLOTS), ContainerLevelAccess.NULL);
    }

    public static IceboxMenu advancedClient(int containerId, Inventory inventory) {
        return new IceboxMenu(ModMenuTypes.ADVANCED_ICEBOX.get(), containerId, inventory, new SimpleContainer(ADVANCED_SLOTS), ContainerLevelAccess.NULL);
    }

    public static IceboxMenu server(int containerId, Inventory inventory, IceboxBlockEntity icebox, ContainerLevelAccess access) {
        MenuType<IceboxMenu> menuType = icebox.getContainerSize() == ADVANCED_SLOTS ? ModMenuTypes.ADVANCED_ICEBOX.get() : ModMenuTypes.SIMPLE_ICEBOX.get();
        return new IceboxMenu(menuType, containerId, inventory, icebox, access);
    }

    private IceboxMenu(MenuType<IceboxMenu> menuType, int containerId, Inventory playerInventory, Container container, ContainerLevelAccess access) {
        super(menuType, containerId);
        this.container = container;
        this.access = access;
        this.iceboxSlots = container.getContainerSize();
        checkContainerSize(container, this.iceboxSlots);
        container.startOpen(playerInventory.player);

        addIceboxSlots(container);
        addPlayerInventory(playerInventory);
    }

    public int iceboxSlots() {
        return this.iceboxSlots;
    }

    private void addIceboxSlots(Container container) {
        if (this.iceboxSlots == SIMPLE_SLOTS) {
            int startX = 71;
            int startY = 22;
            for (int row = 0; row < 2; row++) {
                for (int column = 0; column < 2; column++) {
                    this.addSlot(new IceboxSlot(container, column + row * 2, startX + column * 18, startY + row * 18, preservationMultiplier()));
                }
            }
        } else {
            int startX = 62;
            int startY = 20;
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 3; column++) {
                    this.addSlot(new IceboxSlot(container, column + row * 3, startX + column * 18, startY + row * 18, preservationMultiplier()));
                }
            }
        }
    }

    private void addPlayerInventory(Inventory playerInventory) {
        int inventoryY = this.iceboxSlots == SIMPLE_SLOTS ? 70 : 88;

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                this.addSlot(new Slot(playerInventory, column + row * 9 + 9, 8 + column * 18, inventoryY + row * 18));
            }
        }

        for (int column = 0; column < 9; column++) {
            this.addSlot(new Slot(playerInventory, column, 8 + column * 18, inventoryY + 58));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot.hasItem()) {
            ItemStack source = slot.getItem();
            result = source.copy();
            if (index < this.iceboxSlots) {
                SpoilageManager.restoreBaseDuration(source);
                if (!this.moveItemStackTo(source, this.iceboxSlots, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(source, 0, this.iceboxSlots, false)) {
                return ItemStack.EMPTY;
            }

            if (source.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return result;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(this.access, player, this.iceboxSlots == ADVANCED_SLOTS ? ModBlocks.ADVANCED_ICEBOX.get() : ModBlocks.SIMPLE_ICEBOX.get());
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        this.container.stopOpen(player);
    }

    private double preservationMultiplier() {
        return this.iceboxSlots == ADVANCED_SLOTS ? 3.0D : 2.0D;
    }

    private static final class IceboxSlot extends Slot {
        private final double preservationMultiplier;

        private IceboxSlot(Container container, int slot, int x, int y, double preservationMultiplier) {
            super(container, slot, x, y);
            this.preservationMultiplier = preservationMultiplier;
        }

        @Override
        public int getMaxStackSize() {
            return IceboxBlockEntity.SLOT_LIMIT;
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return IceboxBlockEntity.SLOT_LIMIT;
        }

        @Override
        public void set(ItemStack stack) {
            SpoilageManager.refreshPreserved(stack, this.preservationMultiplier);
            super.set(stack);
        }

        @Override
        public void setByPlayer(ItemStack newStack, ItemStack oldStack) {
            SpoilageManager.refreshPreserved(newStack, this.preservationMultiplier);
            super.setByPlayer(newStack, oldStack);
        }

        @Override
        public ItemStack remove(int amount) {
            ItemStack removed = super.remove(amount);
            SpoilageManager.restoreBaseDuration(removed);
            return removed;
        }

        @Override
        public void onTake(Player player, ItemStack stack) {
            SpoilageManager.restoreBaseDuration(stack);
            super.onTake(player, stack);
        }
    }
}
