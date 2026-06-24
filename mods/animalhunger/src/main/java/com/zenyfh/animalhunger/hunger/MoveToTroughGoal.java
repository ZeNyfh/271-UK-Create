package com.zenyfh.animalhunger.hunger;

import com.zenyfh.animalhunger.config.AnimalHungerConfig;
import com.zenyfh.animalhunger.world.TroughBlockEntity;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;

public class MoveToTroughGoal extends Goal {
    private static final int HUNGER_THRESHOLD = 14;
    private static final double EAT_DISTANCE_SQR = 4.0D;
    private static final double SPEED = 1.0D;

    private final PathfinderMob mob;
    private BlockPos target;

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
        AnimalHungerData.setTroughSearchCooldown(this.mob, gameTime + 100L);
        this.target = findTrough();
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
        Level level = this.mob.level();
        BlockPos origin = this.mob.blockPosition();
        int radius = AnimalHungerConfig.TROUGH_SEARCH_RADIUS.get();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos)) {
                        continue;
                    }
                    double distance = origin.distSqr(pos);
                    if (distance >= bestDistance) {
                        continue;
                    }
                    if (hasValidFood(pos)) {
                        best = pos.immutable();
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private boolean hasValidFood(BlockPos pos) {
        return this.mob.level().getBlockEntity(pos) instanceof TroughBlockEntity trough && trough.hasFoodFor(this.mob);
    }
}
