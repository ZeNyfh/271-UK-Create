package com.foodspoilage.event;

import com.foodspoilage.recipe.RecipeCache;
import com.foodspoilage.recipe.RecipeComplexityManager;
import com.foodspoilage.config.FoodSpoilageConfig;
import com.foodspoilage.spoilage.FoodClassificationManager;
import com.foodspoilage.spoilage.FoodStackData;
import com.foodspoilage.spoilage.SpoilageManager;
import com.foodspoilage.spoilage.SpoilageStage;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.ItemStackedOnOtherEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

public final class FoodSpoilageEvents {
    private FoodSpoilageEvents() {
    }

    public static void register(IEventBus bus) {
        bus.addListener(FoodSpoilageEvents::onServerStarted);
        bus.addListener(FoodSpoilageEvents::onServerStopped);
        bus.addListener(FoodSpoilageEvents::onTagsUpdated);
        bus.addListener(FoodSpoilageEvents::onCrafted);
        bus.addListener(FoodSpoilageEvents::onSmelted);
        bus.addListener(FoodSpoilageEvents::onContainerOpen);
        bus.addListener(FoodSpoilageEvents::onEntityJoin);
        bus.addListener(FoodSpoilageEvents::onEntityTick);
        bus.addListener(FoodSpoilageEvents::onPickup);
        bus.addListener(FoodSpoilageEvents::onStackedOnOther);
        bus.addListener(FoodSpoilageEvents::onUseStart);
        bus.addListener(FoodSpoilageEvents::onUseFinish);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        rebuildRecipes(event.getServer());
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        RecipeCache.get().clear();
        RecipeComplexityManager.get().clear();
    }

    private static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                rebuildRecipes(server);
            }
        }
    }

    private static void rebuildRecipes(MinecraftServer server) {
        RecipeComplexityManager.get().clear();
        RecipeCache.get().rebuild(server.getRecipeManager(), server.registryAccess());
    }

    private static void onCrafted(PlayerEvent.ItemCraftedEvent event) {
        SpoilageManager.ensureInitialized(event.getCrafting());
    }

    private static void onSmelted(PlayerEvent.ItemSmeltedEvent event) {
        SpoilageManager.ensureInitialized(event.getSmelting());
    }

    private static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        for (Slot slot : event.getContainer().slots) {
            ItemStack transformed = SpoilageManager.transformIfRotten(slot.getItem());
            if (transformed != slot.getItem()) {
                slot.set(transformed);
            } else {
                SpoilageManager.refresh(slot.getItem());
            }
        }
    }

    private static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ItemEntity itemEntity)) {
            return;
        }
        itemEntity.setItem(SpoilageManager.transformIfRotten(itemEntity.getItem()));
        SpoilageManager.ensureInitialized(itemEntity.getItem());
    }

    private static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof ItemEntity itemEntity) || itemEntity.level().isClientSide()) {
            return;
        }
        if (itemEntity.tickCount % 100 != 0) {
            return;
        }
        itemEntity.setItem(SpoilageManager.transformIfRotten(itemEntity.getItem()));
        SpoilageManager.refresh(itemEntity.getItem());
    }

    private static void onPickup(ItemEntityPickupEvent.Pre event) {
        SpoilageManager.refresh(event.getItemEntity().getItem());
    }

    private static void onStackedOnOther(ItemStackedOnOtherEvent event) {
        if (event.getPlayer().level().isClientSide() || event.getClickAction() != ClickAction.PRIMARY) {
            return;
        }
        ItemStack carried = event.getCarriedItem();
        ItemStack target = event.getStackedOnItem();
        if (carried.isEmpty() || target.isEmpty() || !ItemStack.isSameItemSameComponents(carried, target)) {
            return;
        }
        if (!FoodClassificationManager.isSpoilageEligible(carried) || !FoodClassificationManager.isSpoilageEligible(target)) {
            return;
        }

        int room = Math.min(target.getMaxStackSize() - target.getCount(), carried.getCount());
        if (room <= 0) {
            return;
        }

        SpoilageManager.averageInto(target, carried, room);
        target.grow(room);
        carried.shrink(room);
        event.getSlot().set(target);
        event.getCarriedSlotAccess().set(carried);
        event.setCanceled(true);
    }

    private static void onUseStart(LivingEntityUseItemEvent.Start event) {
        if (!(event.getEntity().level() instanceof ServerLevel)) {
            return;
        }
        ItemStack stack = event.getItem();
        if (!FoodClassificationManager.isSpoilageEligible(stack)) {
            return;
        }
        ItemStack transformed = SpoilageManager.transformIfRotten(stack);
        if (transformed != stack) {
            event.getEntity().setItemInHand(event.getHand(), transformed);
            event.setCanceled(true);
        } else {
            SpoilageManager.refresh(stack);
        }
    }

    private static void onUseFinish(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof Player player) || player.level().isClientSide()) {
            return;
        }
        ItemStack eaten = event.getItem();
        if (!FoodClassificationManager.isSpoilageEligible(eaten)) {
            return;
        }
        FoodStackData data = SpoilageManager.data(eaten);
        if (data == null) {
            return;
        }
        FoodProperties food = eaten.get(DataComponents.FOOD);
        if (food == null) {
            return;
        }
        SpoilageStage stage = data.stageAt(SpoilageManager.now());
        if (stage == SpoilageStage.STALE) {
            reduceRecentNutrition(player, food, 0.50D);
        } else if (stage == SpoilageStage.SPOILED) {
            reduceRecentNutrition(player, food, 0.80D);
            if (FoodSpoilageConfig.HUNGER_EFFECT.get()) {
                player.addEffect(new MobEffectInstance(MobEffects.HUNGER, 20 * 16, 0));
            }
            if (FoodSpoilageConfig.NAUSEA_EFFECT.get()) {
                player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 20 * 6, 0));
            }
            if (FoodSpoilageConfig.POISON_EFFECT.get()) {
                player.addEffect(new MobEffectInstance(MobEffects.POISON, 20 * 6, 0));
            }
        }
    }

    private static void reduceRecentNutrition(Player player, FoodProperties food, double reduction) {
        FoodData foodData = player.getFoodData();
        int foodLoss = (int) Math.ceil(food.nutrition() * reduction);
        float saturationLoss = (float) (food.nutrition() * food.saturation() * 2.0D * reduction);
        foodData.setFoodLevel(Math.max(0, foodData.getFoodLevel() - foodLoss));
        foodData.setSaturation(Math.max(0.0F, foodData.getSaturationLevel() - saturationLoss));
    }
}
