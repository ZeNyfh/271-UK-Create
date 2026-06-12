package com.foodspoilage.spoilage;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Centralised hooks for starting spoilage when food enters an inventory.
 *
 * <p>These methods intentionally only initialise missing spoilage data. Existing
 * timestamps are not reset, so repeated inventory/menu scans do not refresh food.</p>
 */
public final class InventorySpoilageHooks {
    private InventorySpoilageHooks() {
    }

    public static void onStackEnteredInventory(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        SpoilageManager.ensureInitialized(stack);
    }

    public static void scanContainer(Container container) {
        if (container == null) {
            return;
        }
        int size = container.getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            onStackEnteredInventory(container.getItem(slot));
        }
        container.setChanged();
    }

    public static void scanPlayerInventory(Player player) {
        if (player == null || player.level().isClientSide()) {
            return;
        }
        scanContainer(player.getInventory());
    }

    public static void scanMenu(AbstractContainerMenu menu) {
        if (menu == null) {
            return;
        }
        for (Slot slot : menu.slots) {
            onStackEnteredInventory(slot.getItem());
        }
    }
}
