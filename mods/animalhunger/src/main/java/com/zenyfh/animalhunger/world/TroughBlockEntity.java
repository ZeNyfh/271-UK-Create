package com.zenyfh.animalhunger.world;

import com.zenyfh.animalhunger.hunger.AnimalFood;
import com.zenyfh.animalhunger.hunger.TroughTracker;
import com.zenyfh.animalhunger.registry.ModBlockEntities;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

public class TroughBlockEntity extends BlockEntity implements WorldlyContainer, MenuProvider {
    public static final int SLOT_COUNT = 9;
    private static final int[] SLOTS = {0, 1, 2, 3, 4, 5, 6, 7, 8};

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
    private final Map<ResourceLocation, Boolean> hasFoodCache = new HashMap<>();

    public TroughBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TROUGH.get(), pos, state);
    }

    @Override
    public int getContainerSize() {
        return SLOT_COUNT;
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
        return this.items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack removed = ContainerHelper.removeItem(this.items, slot, amount);
        if (!removed.isEmpty()) {
            this.setChanged();
        }
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack removed = ContainerHelper.takeItem(this.items, slot);
        if (!removed.isEmpty()) {
            this.setChanged();
        }
        return removed;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        this.items.set(slot, stack);
        if (stack.getCount() > this.getMaxStackSize()) {
            stack.setCount(this.getMaxStackSize());
        }
        this.setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return AnimalFood.isAnyAnimalFood(stack);
    }

    @Override
    public void clearContent() {
        this.items.clear();
        this.setChanged();
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return SLOTS;
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
        return TroughBlock.defaultName();
    }

    @Override
    @Nullable
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return TroughMenu.server(containerId, playerInventory, this, ContainerLevelAccess.create(player.level(), this.worldPosition));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, this.items, registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, this.items, registries);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        TroughTracker.register(this.level, this.worldPosition);
        this.updateFilledState();
    }

    @Override
    public void setRemoved() {
        TroughTracker.unregister(this.level, this.worldPosition);
        super.setRemoved();
    }

    @Override
    public void setChanged() {
        super.setChanged();
        this.hasFoodCache.clear();
        this.updateFilledState();
    }

    public boolean hasFoodFor(LivingEntity entity) {
        ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        Boolean cached = this.hasFoodCache.get(entityId);
        if (cached != null) {
            return cached;
        }
        boolean result = findFoodFor(entity).isPresent();
        this.hasFoodCache.put(entityId, result);
        return result;
    }

    public Optional<ItemStack> findFoodFor(LivingEntity entity) {
        for (ItemStack stack : this.items) {
            if (!stack.isEmpty() && AnimalFood.canEntityEat(entity, stack)) {
                return Optional.of(stack);
            }
        }
        return Optional.empty();
    }

    public int consumeFoodFor(LivingEntity entity) {
        for (int i = 0; i < this.items.size(); i++) {
            ItemStack stack = this.items.get(i);
            if (!stack.isEmpty() && AnimalFood.canEntityEat(entity, stack)) {
                int restore = AnimalFood.foodValue(stack);
                stack.shrink(1);
                if (stack.isEmpty()) {
                    this.items.set(i, ItemStack.EMPTY);
                }
                this.setChanged();
                return restore;
            }
        }
        return 0;
    }

    public int storedFoodCount() {
        int count = 0;
        for (ItemStack stack : this.items) {
            if (!stack.isEmpty() && AnimalFood.isAnyAnimalFood(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private void updateFilledState() {
        if (this.level == null || this.level.isClientSide()) {
            return;
        }
        BlockState state = this.getBlockState();
        if (!state.hasProperty(TroughBlock.HAS_FOOD)) {
            return;
        }
        boolean hasFood = !this.isEmpty();
        if (state.getValue(TroughBlock.HAS_FOOD) != hasFood) {
            this.level.setBlock(
                this.worldPosition,
                state.setValue(TroughBlock.HAS_FOOD, hasFood),
                Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS
            );
        }
    }
}
