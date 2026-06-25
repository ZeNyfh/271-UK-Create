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

    public static boolean onStackEnteredInventory(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        boolean hadData = SpoilageManager.existingData(stack) != null;
        SpoilageManager.ensureInitialized(stack);
        return !hadData && SpoilageManager.existingData(stack) != null;
    }

    public static boolean scanContainer(Container container) {
        if (container == null) {
            return false;
        }
        boolean changed = false;
        int size = container.getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            changed |= onStackEnteredInventory(container.getItem(slot));
        }
        if (changed) {
            container.setChanged();
        }
        return changed;
    }

    public static boolean scanPlayerInventory(Player player) {
        if (player == null || player.level().isClientSide()) {
            return false;
        }
        long startNanos = System.nanoTime();
        boolean changed = scanContainer(player.getInventory());
        FoodSpoilagePerf.playerInventoryScan(System.nanoTime() - startNanos, changed);
        return changed;
    }

    public static boolean scanMenu(AbstractContainerMenu menu) {
        if (menu == null) {
            return false;
        }
        long startNanos = System.nanoTime();
        boolean changed = false;
        for (Slot slot : menu.slots) {
            changed |= onStackEnteredInventory(slot.getItem());
        }
        FoodSpoilagePerf.menuScan(System.nanoTime() - startNanos, changed);
        return changed;
    }
}
