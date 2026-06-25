package com.zenyfh.animalhunger.hunger;

import com.zenyfh.animalhunger.config.AnimalHungerConfig;
import com.zenyfh.animalhunger.world.TroughBlockEntity;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;

public class MoveToTroughGoal extends Goal {
    private static final int HUNGER_THRESHOLD = 14;
    private static final double EAT_DISTANCE_SQR = 4.0D;
    private static final double SPEED = 1.0D;

    private final PathfinderMob mob;
    private BlockPos target;
    private BlockPos cachedTarget;

    public MoveToTroughGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (this.mob.level().isClientSide() || !AnimalHungerData.isHungry(this.mob, HUNGER_THRESHOLD)) {
            return false;
        }
        long gameTime = this.mob.level().getGameTime();
        if (!AnimalHungerData.troughSearchReady(this.mob, gameTime)) {
            return false;
        }
        if (this.cachedTarget != null && hasValidFood(this.cachedTarget)) {
            this.target = this.cachedTarget;
            AnimalHungerData.setTroughSearchCooldown(this.mob, gameTime + 80L);
            return true;
        }
        this.target = findTrough();
        this.cachedTarget = this.target;
        AnimalHungerData.setTroughSearchCooldown(this.mob, gameTime + (this.target == null ? 200L : 100L));
        return this.target != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.target != null && AnimalHungerData.isHungry(this.mob, HUNGER_THRESHOLD) && hasValidFood(this.target);
    }

    @Override
    public void start() {
        if (this.target != null) {
            this.mob.getNavigation().moveTo(this.target.getX() + 0.5D, this.target.getY(), this.target.getZ() + 0.5D, SPEED);
        }
    }

    @Override
    public void tick() {
        if (this.target == null) {
            return;
        }
        if (this.mob.distanceToSqr(this.target.getX() + 0.5D, this.target.getY() + 0.5D, this.target.getZ() + 0.5D) <= EAT_DISTANCE_SQR) {
            if (this.mob.level().getBlockEntity(this.target) instanceof TroughBlockEntity trough) {
                int amount = trough.consumeFoodFor(this.mob);
                if (amount > 0) {
                    AnimalHungerEvents.restoreFromTrough(this.mob, amount);
                    AnimalHungerData.setTroughSearchCooldown(this.mob, this.mob.level().getGameTime() + 60L);
                    this.target = null;
                    this.mob.getNavigation().stop();
                }
            }
        } else if (this.mob.getNavigation().isDone()) {
            this.mob.getNavigation().moveTo(this.target.getX() + 0.5D, this.target.getY(), this.target.getZ() + 0.5D, SPEED);
        }
    }

    @Override
    public void stop() {
        this.target = null;
    }

    private BlockPos findTrough() {
        int radius = AnimalHungerConfig.TROUGH_SEARCH_RADIUS.get();
        return TroughTracker.nearestTroughWithFood(this.mob, radius);
    }

    private boolean hasValidFood(BlockPos pos) {
        return this.mob.level().getBlockEntity(pos) instanceof TroughBlockEntity trough && trough.hasFoodFor(this.mob);
    }
}
