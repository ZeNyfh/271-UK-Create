package com.zenyfh.animalhunger.world;

import com.zenyfh.animalhunger.hunger.AnimalFood;
import com.zenyfh.animalhunger.registry.ModBlocks;
import com.zenyfh.animalhunger.registry.ModMenuTypes;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class TroughMenu extends AbstractContainerMenu {
    public static final int TROUGH_SLOTS = 9;
    public static final int PLAYER_INVENTORY_Y = 84;

    private final Container container;
    private final ContainerLevelAccess access;

    public static TroughMenu client(int containerId, Inventory inventory) {
        return new TroughMenu(containerId, inventory, new SimpleContainer(TROUGH_SLOTS), ContainerLevelAccess.NULL);
    }

    public static TroughMenu server(int containerId, Inventory inventory, TroughBlockEntity trough, ContainerLevelAccess access) {
        return new TroughMenu(containerId, inventory, trough, access);
    }

    private TroughMenu(int containerId, Inventory playerInventory, Container container, ContainerLevelAccess access) {
        super(ModMenuTypes.TROUGH.get(), containerId);
        this.container = container;
        this.access = access;
        checkContainerSize(container, TROUGH_SLOTS);
        container.startOpen(playerInventory.player);
        addTroughSlots(container);
        addPlayerInventory(playerInventory);
    }

    private void addTroughSlots(Container container) {
        int startX = 62;
        int startY = 17;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                this.addSlot(new AnimalFoodSlot(container, column + row * 3, startX + column * 18, startY + row * 18));
            }
        }
    }

    private void addPlayerInventory(Inventory playerInventory) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                this.addSlot(new Slot(playerInventory, column + row * 9 + 9, 8 + column * 18, PLAYER_INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            this.addSlot(new Slot(playerInventory, column, 8 + column * 18, PLAYER_INVENTORY_Y + 58));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot.hasItem()) {
            ItemStack source = slot.getItem();
            result = source.copy();
            if (index < TROUGH_SLOTS) {
                if (!this.moveItemStackTo(source, TROUGH_SLOTS, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else if (AnimalFood.isAnyAnimalFood(source)) {
                if (!this.moveItemStackTo(source, 0, TROUGH_SLOTS, false)) {
                    return ItemStack.EMPTY;
                }
            } else {
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
        return stillValid(this.access, player, ModBlocks.TROUGH.get());
    }

    public List<Component> supportedAnimalDisplayNames() {
        return AnimalFood.supportedAnimalDisplayNames(this.container);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        this.container.stopOpen(player);
    }

    private static final class AnimalFoodSlot extends Slot {
        private AnimalFoodSlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return AnimalFood.isAnyAnimalFood(stack);
        }
    }
}
