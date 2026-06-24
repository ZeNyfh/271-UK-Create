package com.zenyfh.animalhunger.hunger;

import com.zenyfh.animalhunger.config.AnimalHungerConfig;
import com.zenyfh.animalhunger.registry.ModTags;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class GrazeGoal extends Goal {
    private static final int HUNGER_THRESHOLD = 16;
    private static final double EAT_DISTANCE_SQR = 4.0D;
    private static final double SPEED = 1.0D;

    private final PathfinderMob mob;
    private BlockPos target;

    public GrazeGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (this.mob.level().isClientSide() || !AnimalHungerData.isHungry(this.mob, HUNGER_THRESHOLD)) {
            return false;
        }
        long gameTime = this.mob.level().getGameTime();
        if (!AnimalHungerData.grassSearchReady(this.mob, gameTime) || !AnimalHungerData.grazingReady(this.mob, gameTime)) {
            return false;
        }
        AnimalHungerData.setGrassSearchCooldown(this.mob, gameTime + 120L);
        this.target = findGrazingBlock();
        return this.target != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.target != null && AnimalHungerData.isHungry(this.mob, HUNGER_THRESHOLD) && isGrazingBlock(this.mob.level().getBlockState(this.target));
    }

    @Override
    public void start() {
        if (this.target != null) {
            BlockPos standAt = standPosition(this.target);
            this.mob.getNavigation().moveTo(standAt.getX() + 0.5D, standAt.getY(), standAt.getZ() + 0.5D, SPEED);
        }
    }

    @Override
    public void tick() {
        if (this.target == null) {
            return;
        }
        BlockPos standAt = standPosition(this.target);
        if (this.mob.distanceToSqr(standAt.getX() + 0.5D, standAt.getY() + 0.5D, standAt.getZ() + 0.5D) <= EAT_DISTANCE_SQR) {
            if (eatGrazingBlock(this.target)) {
                AnimalHungerEvents.restoreFromGrazing(this.mob);
                AnimalHungerData.setGrazingCooldown(this.mob, this.mob.level().getGameTime() + AnimalHungerConfig.GRAZING_COOLDOWN_TICKS.get());
                this.target = null;
                this.mob.getNavigation().stop();
            }
        } else if (this.mob.getNavigation().isDone()) {
            this.mob.getNavigation().moveTo(standAt.getX() + 0.5D, standAt.getY(), standAt.getZ() + 0.5D, SPEED);
        }
    }

    @Override
    public void stop() {
        this.target = null;
    }

    private BlockPos findGrazingBlock() {
        Level level = this.mob.level();
        BlockPos origin = this.mob.blockPosition();
        int radius = AnimalHungerConfig.GRASS_SEARCH_RADIUS.get();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (!isGrazingBlock(state)) {
                        continue;
                    }
                    BlockPos standAt = standPosition(pos);
                    if (!level.getBlockState(standAt).isAir() && !standAt.equals(pos)) {
                        continue;
                    }
                    double distance = origin.distSqr(standAt);
                    if (distance < bestDistance) {
                        best = pos.immutable();
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private BlockPos standPosition(BlockPos grazingBlock) {
        BlockState state = this.mob.level().getBlockState(grazingBlock);
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.HAY_BLOCK) ? grazingBlock.above() : grazingBlock;
    }

    private boolean eatGrazingBlock(BlockPos pos) {
        Level level = this.mob.level();
        BlockState state = level.getBlockState(pos);
        if (!isGrazingBlock(state)) {
            return false;
        }
        if (state.is(Blocks.GRASS_BLOCK)) {
            if (AnimalHungerConfig.GRAZING_DAMAGES_GRASS_BLOCKS.get()) {
                level.setBlock(pos, Blocks.DIRT.defaultBlockState(), 3);
            }
            return true;
        }
        if (state.is(Blocks.HAY_BLOCK)) {
            return true;
        }
        level.destroyBlock(pos, false, this.mob);
        return true;
    }

    private static boolean isGrazingBlock(BlockState state) {
        return state.is(ModTags.GRAZING_BLOCKS);
    }
}
