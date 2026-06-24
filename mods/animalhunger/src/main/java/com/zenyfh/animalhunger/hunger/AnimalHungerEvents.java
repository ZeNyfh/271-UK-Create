package com.zenyfh.animalhunger.hunger;

import com.zenyfh.animalhunger.AnimalHunger;
import com.zenyfh.animalhunger.config.AnimalHungerConfig;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

public final class AnimalHungerEvents {
    private static final Set<Entity> GOALS_ADDED = Collections.newSetFromMap(new WeakHashMap<>());
    private static final int TROUGH_GOAL_PRIORITY = 2;
    private static final int GRAZE_GOAL_PRIORITY = 3;

    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !AnimalHungerConfig.HUNGER_ENABLED.get()) {
            return;
        }
        if (event.getEntity() instanceof LivingEntity living && isSupported(living)) {
            AnimalHungerData.getOrCreate(living, event.getLevel().getGameTime());
            addGoals(living);
            debug("tracking {} at {}", entityId(living), living.blockPosition());
        }
    }

    @SubscribeEvent
    public void onEntityTick(EntityTickEvent.Post event) {
        if (!AnimalHungerConfig.HUNGER_ENABLED.get() || event.getEntity().level().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity living) || !isSupported(living) || !living.isAlive()) {
            return;
        }
        long gameTime = living.level().getGameTime();
        AnimalHungerData.getOrCreate(living, gameTime);
        addGoals(living);
        if (Math.floorMod(living.getId(), 20) != Math.floorMod((int) gameTime, 20)) {
            return;
        }
        int before = AnimalHungerData.hunger(living);
        AnimalHungerData.drainCatchUp(living, gameTime);
        int after = AnimalHungerData.hunger(living);
        if (after < before) {
            debug("{} hunger {} -> {}", entityId(living), before, after);
        }
        if (after <= 0) {
            debug("{} starved at {}", entityId(living), living.blockPosition());
            living.hurt(living.damageSources().starve(), living.getMaxHealth() + 1000.0F);
        }
    }

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        if (player.level().isClientSide() || !AnimalHungerConfig.HUNGER_ENABLED.get()) {
            return;
        }
        if (!(event.getTarget() instanceof LivingEntity target) || !isSupported(target)) {
            return;
        }
        long gameTime = player.level().getGameTime();
        AnimalHungerData.getOrCreate(target, gameTime);
        if (AnimalHungerData.hunger(target) >= AnimalHungerData.maxHunger()) {
            return;
        }
        ItemStack stack = player.getItemInHand(event.getHand());
        if (!AnimalFood.canEntityEat(target, stack)) {
            return;
        }
        int restored = AnimalHungerData.addHunger(target, AnimalFood.foodValue(stack), gameTime);
        if (restored <= 0) {
            return;
        }
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        if (target.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.HEART, target.getX(), target.getY() + target.getBbHeight() + 0.15D, target.getZ(), 3, 0.25D, 0.2D, 0.25D, 0.02D);
        }
        target.level().playSound(null, target.blockPosition(), SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 0.7F, 1.0F);
        debug("{} hand-fed {} restored {}", entityId(target), BuiltInRegistries.ITEM.getKey(stack.getItem()), restored);
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    public static boolean isSupported(LivingEntity entity) {
        ResourceLocation id = entityId(entity);
        if (entity instanceof Animal || entity instanceof Bat) {
            return true;
        }
        return "wildernature".equals(id.getNamespace()) && AnimalHungerConfig.ENABLE_WILDERNATURE_SUPPORT.get() && switch (id.getPath()) {
            case "deer", "bison", "boar", "dog", "hedgehog", "minisheep", "owl", "squirrel" -> true;
            default -> false;
        };
    }

    public static ResourceLocation entityId(LivingEntity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
    }

    public static int restoreFromTrough(LivingEntity entity, int amount) {
        int restored = AnimalHungerData.addHunger(entity, amount, entity.level().getGameTime());
        if (restored > 0) {
            entity.level().playSound(null, entity.blockPosition(), SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 0.6F, 0.95F);
            debug("{} ate from trough restored {}", entityId(entity), restored);
        }
        return restored;
    }

    public static int restoreFromGrazing(LivingEntity entity) {
        int restored = AnimalHungerData.addHunger(entity, AnimalHungerConfig.GRAZING_RESTORES.get(), entity.level().getGameTime());
        if (restored > 0) {
            entity.level().playSound(null, entity.blockPosition(), SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 0.45F, 1.1F);
            debug("{} grazed restored {}", entityId(entity), restored);
        }
        return restored;
    }

    private static void addGoals(LivingEntity entity) {
        if (!(entity instanceof PathfinderMob mob) || !GOALS_ADDED.add(entity)) {
            return;
        }
        mob.goalSelector.addGoal(TROUGH_GOAL_PRIORITY, new MoveToTroughGoal(mob));
        mob.goalSelector.addGoal(GRAZE_GOAL_PRIORITY, new GrazeGoal(mob));
    }

    private static void debug(String message, Object... args) {
        if (AnimalHungerConfig.DEBUG_ANIMAL_HUNGER.get()) {
            AnimalHunger.LOGGER.info(message, args);
        }
    }
}
